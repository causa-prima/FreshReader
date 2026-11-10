package io.github.causaprima.freshreader.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.io.File
import java.time.Clock
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The result of talking to the Server. */
sealed interface Outcome {
    data object Ok : Outcome
    data object WrongCredentials : Outcome
    data object InvalidServerUrl : Outcome
    data class Unreachable(val reason: String) : Outcome
    data class ServerError(val httpStatus: Int) : Outcome
}

data class Article(
    val id: String,
    val title: String,
    /** The Server's Feed id, e.g. `feed/12`. */
    val feedId: String,
    val feedTitle: String,
    /** Publication time in epoch seconds. */
    val published: Long,
    val url: String?,
    /** The Feed Content: the Article's HTML exactly as the Server delivers it. */
    val content: String = "",
    /** Read during this snapshot; read Articles stay in the list, greyed out, until the next refresh. */
    val read: Boolean = false,
)

/** Whether the reader's read marks have reached the Server. */
sealed interface SyncStatus {
    data object Synced : SyncStatus
    /** [count] read marks are waiting to be sent, or being retried. */
    data class Pending(val count: Int) : SyncStatus
    /** [count] read marks could not be sent, and retrying would not help; [reason] says why. */
    data class Failed(val count: Int, val reason: Outcome) : SyncStatus
}

/** Whom FreshReader reads as: a user on a Server. */
data class Account(val serverUrl: String, val username: String)

/** One Category's part of the list snapshot. */
data class Section(val category: String, val articles: List<Article>) {
    val unreadCount: Int get() = articles.count { !it.read }
}

/**
 * FreshReader's UI-independent core: the single seam the UI talks to.
 */
class Core(
    storeDir: File,
    secretBox: SecretBox,
    httpClient: OkHttpClient = OkHttpClient(),
    /** Time zone and locale of the dates shown to the reader. */
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
    /** The time that decides when a fallback favicon is looked at again. */
    clock: Clock = Clock.systemUTC(),
    /** Wait before the first retry of read marks after a transient failure; doubles with each retry. */
    private val firstRetryDelay: Duration = 2.seconds,
) : AutoCloseable {
    private val dateFormat = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(locale)
        .withZone(zone)
    private val http = httpClient
    private val followIt = FollowIt(httpClient)
    private val credentialStore = CredentialStore(storeDir, secretBox)
    @Volatile
    private var api: GoogleReaderApi? = null
    private val sessionLock = Mutex()
    private val readingOrderStore = ReadingOrderStore(storeDir)
    private var readingOrder: List<String> = readingOrderStore.load()
    private val categoryColourStore = CategoryColourStore(storeDir)
    private var chosenCategoryColours: Map<String, CategoryColour> = categoryColourStore.load()
    private val feedSettingsStore = FeedSettingsStore(storeDir)
    private var feedSettings: Map<String, FeedSettings> = feedSettingsStore.load()
    private var unread: List<Article> = emptyList()
    private var categoryOfFeed: Map<String, String> = emptyMap()
    @Volatile
    private var feedInfo: Map<String, GoogleReaderApi.FeedInfo> = emptyMap()
    private val favicons = Favicons(httpClient)
    private val faviconFallbacks = FaviconFallbacks(httpClient, storeDir, clock)
    /** Ids of the snapshot's Articles that the reader has read since the last refresh. */
    private val readIds = mutableSetOf<String>()

    /** Read marks not yet confirmed by the Server, as Article ids; mirrored in [outboxStore]. */
    private val outboxStore = ReadOutboxStore(storeDir)
    private val outbox = LinkedHashSet(outboxStore.load())
    private val outboxLock = Mutex()
    private val _syncStatus = MutableStateFlow(if (outbox.isEmpty()) SyncStatus.Synced else SyncStatus.Pending(outbox.size))
    private val sendRequests = Channel<Unit>(Channel.CONFLATED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch { for (request in sendRequests) sendReadMarks() }
    }

    /** Whether all read marks have reached the Server; changes in the background. */
    val syncStatus: StateFlow<SyncStatus> = _syncStatus

    /** The current list snapshot, sectioned by Category; replaced by [refresh], rearranged by [setReadingOrder]. */
    var sections: List<Section> = emptyList()
        private set

    /** All Articles of the snapshot in display order, across sections. */
    val articles: List<Article> get() = sections.flatMap { it.articles }

    /**
     * All Categories known from the last refresh, in display order: the Reading Order first, then the
     * rest alphabetically. Includes Categories without unread Articles, so that every one can be ordered.
     */
    val categories: List<String>
        get() = inReadingOrder(categoryOfFeed.values + sections.map { it.category }, readingOrder)

    /** Whether credentials from an earlier successful login are stored on the device. */
    val hasStoredCredentials: Boolean get() = credentialStore.load() != null

    /** The Server and user of the stored credentials, e.g. to offer them when changing the Server; null before a login. */
    val account: Account? get() = credentialStore.load()?.let { Account(it.serverUrl, it.username) }

    /**
     * Logs in with the API password; on success the credentials are stored encrypted. Logging in to another
     * Server or as another user drops the read marks not yet sent, which belong to the previous one; a failed
     * login keeps the previous credentials.
     */
    suspend fun logIn(serverUrl: String, username: String, apiPassword: String): Outcome {
        val candidate = GoogleReaderApi.forServer(http, serverUrl) ?: return Outcome.InvalidServerUrl
        val outcome = candidate.logIn(username, apiPassword)
        if (outcome == Outcome.Ok) {
            val previous = account
            credentialStore.save(Credentials(serverUrl, username, apiPassword))
            api = candidate
            if (previous != null && previous != Account(serverUrl, username)) {
                outboxLock.withLock {
                    outbox.clear()
                    withContext(Dispatchers.IO) { outboxStore.save(outbox) }
                    _syncStatus.value = SyncStatus.Synced
                }
            }
        }
        return outcome
    }

    /**
     * Replaces the list snapshot with all unread Articles, logging in with stored credentials if needed.
     * Articles whose read marks are still pending stay out of it; sending them is retried.
     */
    suspend fun refresh(): Outcome {
        sendRequests.trySend(Unit)
        val api = when (val session = session()) {
            is ApiResult.Success -> session.value
            is ApiResult.Failure -> return session.outcome
        }
        val feeds = when (val result = api.feeds()) {
            is ApiResult.Success -> result.value
            is ApiResult.Failure -> return result.outcome
        }
        val unread = when (val result = api.unreadArticles()) {
            is ApiResult.Success -> result.value
            is ApiResult.Failure -> return result.outcome
        }
        val pending = outboxLock.withLock { outbox.toSet() }
        this.unread = unread.filterNot { it.id in pending }
        categoryOfFeed = feeds.mapValues { it.value.category }
        feedInfo = feeds
        readIds.clear()
        rebuildSections()
        return Outcome.Ok
    }

    /**
     * The read rule: opening the Article at [position] in [articles] marks it and every earlier Article
     * as read, across Categories. Articles already read stay as they are.
     */
    suspend fun openArticle(position: Int) {
        val newlyRead = articles.take(position + 1).filterNot { it.read }.map { it.id }
        if (newlyRead.isEmpty()) return
        readIds += newlyRead
        rebuildSections()
        outboxLock.withLock {
            outbox += newlyRead
            withContext(Dispatchers.IO) { outboxStore.save(outbox) }
            _syncStatus.value = SyncStatus.Pending(outbox.size)
        }
        sendRequests.trySend(Unit)
    }

    /**
     * Marks every not-yet-read Article of the snapshot as read. Only the snapshot's Article ids are sent,
     * so Articles that arrived on the Server since the last refresh stay unread.
     */
    suspend fun markAllAsRead() = openArticle(articles.lastIndex)

    /**
     * Sends the outbox to the Server in batches. Transient failures are retried with exponential backoff
     * and stay invisible as Pending; any other failure stops sending until the next refresh or opened Article.
     */
    private suspend fun sendReadMarks() {
        var retryDelay = firstRetryDelay
        while (true) {
            val batch = outboxLock.withLock { outbox.take(READ_MARK_BATCH) }
            if (batch.isEmpty()) return
            val outcome = when (val session = session()) {
                is ApiResult.Success -> session.value.markRead(batch)
                is ApiResult.Failure -> session.outcome
            }
            outboxLock.withLock {
                if (outcome == Outcome.Ok) {
                    outbox -= batch.toSet()
                    outboxStore.save(outbox)
                }
                _syncStatus.value = when {
                    outcome != Outcome.Ok && !outcome.isTransient() -> SyncStatus.Failed(outbox.size, outcome)
                    outbox.isEmpty() -> SyncStatus.Synced
                    else -> SyncStatus.Pending(outbox.size)
                }
            }
            when {
                outcome == Outcome.Ok -> retryDelay = firstRetryDelay
                outcome.isTransient() -> {
                    // A refresh or another opened Article retries at once, e.g. after the network is back.
                    withTimeoutOrNull(retryDelay) { sendRequests.receive() }
                    retryDelay = (retryDelay * 2).coerceAtMost(MAX_RETRY_DELAY)
                }
                else -> return
            }
        }
    }

    /** Whether retrying may help: the Server is unreachable, overloaded or failed internally. */
    private fun Outcome.isTransient() = this is Outcome.Unreachable ||
        this is Outcome.ServerError && (httpStatus >= 500 || httpStatus == 408 || httpStatus == 429)

    /**
     * The logged-in API client, logging in with stored credentials if needed. Refresh and the sending
     * of read marks may ask at the same time; the lock lets only one of them log in.
     */
    private suspend fun session(): ApiResult<GoogleReaderApi> = sessionLock.withLock {
        api?.let { return ApiResult.Success(it) }
        val stored = credentialStore.load() ?: return ApiResult.Failure(Outcome.WrongCredentials)
        val candidate = GoogleReaderApi.forServer(http, stored.serverUrl)
            ?: return ApiResult.Failure(Outcome.InvalidServerUrl)
        when (val outcome = candidate.logIn(stored.username, stored.apiPassword)) {
            Outcome.Ok -> ApiResult.Success(candidate).also { api = candidate }
            else -> ApiResult.Failure(outcome)
        }
    }

    private fun rebuildSections() {
        sections = buildSections(unread.map { it.copy(read = it.id in readIds) }, categoryOfFeed, readingOrder)
    }

    /**
     * The favicon of [article]'s Feed as image bytes; null if the Feed has none of its own or it cannot be loaded.
     * If the Server only has it as SVG, which Android cannot show, the Feed's site's other favicon stands in.
     */
    suspend fun favicon(article: Article): ByteArray? {
        val feed = feedInfo[article.feedId] ?: return null
        val url = feed.iconUrl ?: return null
        return withContext(Dispatchers.IO) {
            when (val icon = favicons.load(url)) {
                is ServerFavicon.Image -> icon.bytes.also { feed.site?.let(faviconFallbacks::drop) }
                ServerFavicon.Svg -> feed.site?.let { faviconFallbacks.find(it) }
                ServerFavicon.None -> null
            }
        }
    }

    /** Prepares [article] for the Article View: an HTML document with header and Feed Content. */
    fun prepareArticleView(article: Article): String =
        articleViewDocument(article, feedSettings(article.feedId), dateFormat)

    /**
     * Whether the in-app browser, loading an Original Page as [PageLoad.TextAndLayout] for the Reader View, lets a
     * request through: the page itself, [isPageItself], and its stylesheets, by what the request [accept]s. Scripts,
     * ads, trackers, images and fonts are not needed for the main content; see ADR 0002.
     */
    fun readerViewLoads(isPageItself: Boolean, accept: String?): Boolean =
        isPageItself || accept?.startsWith("text/css") == true

    /** The script to run in a loaded Original Page; [readerView] reads its result. */
    val readerViewScript: String get() = READER_VIEW_SCRIPT

    /**
     * The Reader View of [article], from [report]: the result of the Reader View script in the in-app browser,
     * as `evaluateJavascript` returns it, after it was asked to load [pageUrl], the Article's Original Page, as
     * [pageLoad]. Loaded as text and layout only, the page's scripts do not run, so neither does its consent manager.
     */
    fun readerView(article: Article, pageUrl: String, pageLoad: PageLoad, report: String): ReaderView {
        val page = pageReport(report) ?: return ReaderView.Failed
        if (page.loading) return ReaderView.NotYet
        if (page.url != null && !isSamePage(page.url, pageUrl)) return ReaderView.ShowPage
        if (pageLoad == PageLoad.Complete) page.tcf?.let { consentManager ->
            if (!consentManager.loaded) return ReaderView.NotYet
            if (consentManager.displayStatus == "visible") return ReaderView.ShowPage
        }
        val content = page.content ?: return ReaderView.Failed
        return ReaderView.Ready(articleViewDocument(article, feedSettings(article.feedId), dateFormat, content))
    }

    /**
     * The Category Colour of [category]: the reader's choice, else one picked automatically that differs from its
     * neighbours'. Counted over all [categories], also those without unread Articles, so that a Category keeps
     * its colour when another one has nothing to read.
     */
    fun categoryColour(category: String): CategoryColour =
        categoryColours(categories, chosenCategoryColours)[category]
            ?: chosenCategoryColours[category] ?: CategoryColour.entries.first()

    /** The Category Colour the reader chose for [category]; null if it is picked automatically. */
    fun chosenCategoryColour(category: String): CategoryColour? = chosenCategoryColours[category]

    /** Sets the Category Colour of [category], kept by its name; null lets it be picked automatically again. */
    suspend fun setCategoryColour(category: String, colour: CategoryColour?) {
        val colours = if (colour == null) chosenCategoryColours - category else chosenCategoryColours + (category to colour)
        withContext(Dispatchers.IO) { categoryColourStore.save(colours) }
        chosenCategoryColours = colours
    }

    /** Replaces the Reading Order: the reader's chosen sequence of Categories, by name. */
    suspend fun setReadingOrder(order: List<String>) {
        withContext(Dispatchers.IO) { readingOrderStore.save(order) }
        readingOrder = order
        rebuildSections()
    }

    /**
     * The link to [article]'s Original Page, for showing, opening and sharing it; null if the Feed gives none.
     */
    suspend fun originalPageUrl(article: Article): String? {
        val link = article.url ?: return null
        return withContext(Dispatchers.IO) { followIt.unwrap(link) }
    }

    /** The Feed Settings of the Feed [feedId]; defaults for a Feed the reader has not changed. */
    fun feedSettings(feedId: String): FeedSettings = feedSettings[feedId] ?: FeedSettings()

    /** Replaces the Feed Settings of the Feed [feedId]; the next prepared Article View follows them. */
    suspend fun setFeedSettings(feedId: String, settings: FeedSettings) {
        val changed = feedSettings + (feedId to settings)
        withContext(Dispatchers.IO) { feedSettingsStore.save(changed) }
        feedSettings = changed
    }

    /** Stops sending read marks; pending ones stay stored for the next Core. */
    override fun close() = scope.cancel()

    private companion object {
        /** Read marks per `edit-tag` request. */
        const val READ_MARK_BATCH = 100
        val MAX_RETRY_DELAY = 5.minutes
    }
}
