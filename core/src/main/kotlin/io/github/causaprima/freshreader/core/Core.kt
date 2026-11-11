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
    /** The time that decides when a fallback favicon is looked at again, and when the Reader View gives up on a page. */
    private val clock: Clock = Clock.systemUTC(),
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

    /**
     * Changes to the list snapshot and to the reader's settings are applied one after another, in the order they
     * were asked for; the fields below change only while it is held.
     */
    private val changeLock = Mutex()
    private val readingOrderStore = ReadingOrderStore(storeDir)
    @Volatile
    private var readingOrder: List<String> = readingOrderStore.load()
    private val categoryColourStore = CategoryColourStore(storeDir)
    @Volatile
    private var chosenCategoryColours: Map<String, CategoryColour> = categoryColourStore.load()
    private val feedSettingsStore = FeedSettingsStore(storeDir)
    @Volatile
    private var feedSettings: Map<String, FeedSettings> = feedSettingsStore.load()
    private var unread: List<Article> = emptyList()
    @Volatile
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

    private val _snapshot = MutableStateFlow<List<Section>>(emptyList())

    /**
     * The list snapshot, sectioned by Category, as it changes: replaced by [refresh], rearranged by
     * [setReadingOrder], and its Articles greyed out as they are read.
     */
    val snapshot: StateFlow<List<Section>> = _snapshot

    /** The current list snapshot; see [snapshot]. */
    val sections: List<Section> get() = _snapshot.value

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

    /**
     * The Server, as its FreshRSS root URL, and user of the stored credentials, e.g. to offer them when changing the
     * Server; null before a login.
     */
    val account: Account?
        get() = credentialStore.load()?.let { Account(GoogleReaderApi.serverRoot(it.serverUrl) ?: it.serverUrl, it.username) }

    /**
     * Logs in with the API password; on success the credentials are stored encrypted. Logging in to another
     * Server or as another user drops the read marks not yet sent, which belong to the previous one; a failed
     * login keeps the previous credentials.
     */
    suspend fun logIn(serverUrl: String, username: String, apiPassword: String): Outcome {
        val root = GoogleReaderApi.serverRoot(serverUrl) ?: return Outcome.InvalidServerUrl
        val candidate = GoogleReaderApi.forServer(http, root) ?: return Outcome.InvalidServerUrl
        val outcome = candidate.logIn(username, apiPassword)
        if (outcome != Outcome.Ok) return outcome
        // Under the session lock, so that no stored login replaces this one, and the previous Account's read marks are
        // dropped before anything can be sent with the new one; see sendReadMarks.
        sessionLock.withLock {
            val previous = account
            if (previous != null && previous != Account(root, username)) {
                outboxLock.withLock {
                    outbox.clear()
                    withContext(Dispatchers.IO) { outboxStore.save(outbox) }
                    _syncStatus.value = SyncStatus.Synced
                }
            }
            withContext(Dispatchers.IO) { credentialStore.save(Credentials(root, username, apiPassword)) }
            api = candidate
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
        changeLock.withLock {
            val pending = outboxLock.withLock { outbox.toSet() }
            this.unread = unread.filterNot { it.id in pending }
            categoryOfFeed = feeds.mapValues { it.value.category }
            feedInfo = feeds
            readIds.clear()
            rebuildSections()
        }
        return Outcome.Ok
    }

    /**
     * The read rule: opening [article] marks it and every Article before it in the current list snapshot as read,
     * across Categories. Articles already read stay as they are; an Article no longer in the snapshot, e.g. dropped
     * by a refresh, marks nothing.
     */
    suspend fun openArticle(article: Article) = changeLock.withLock {
        markReadUpTo(articles.indexOfFirst { it.id == article.id })
    }

    /**
     * Marks every not-yet-read Article of the snapshot as read. Only the snapshot's Article ids are sent,
     * so Articles that arrived on the Server since the last refresh stay unread.
     */
    suspend fun markAllAsRead() = changeLock.withLock { markReadUpTo(articles.lastIndex) }

    private suspend fun markReadUpTo(position: Int) {
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
     * Sends the outbox to the Server in batches. Transient failures are retried with exponential backoff
     * and stay invisible as Pending; any other failure stops sending until the next refresh or opened Article.
     */
    private suspend fun sendReadMarks() {
        var retryDelay = firstRetryDelay
        while (true) {
            val taken = outboxLock.withLock { outbox.take(READ_MARK_BATCH) }
            if (taken.isEmpty()) return
            val session = session()
            // A login to another Account in between has dropped the read marks taken, which were not its own.
            val batch = outboxLock.withLock { taken.filter { it in outbox } }
            if (batch.isEmpty()) continue
            val outcome = when (session) {
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
        _snapshot.value = buildSections(unread.map { it.copy(read = it.id in readIds) }, categoryOfFeed, readingOrder)
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
     * What the Article View shows of [article], following its Feed's View Mode: the prepared Feed Content, or the
     * Reader View or Original Page of its real page, see [originalPageUrl]. An Article whose Feed gives no link to its
     * page is shown as Feed Content.
     */
    suspend fun articlePage(article: Article): ArticlePage {
        val viewMode = if (article.url == null) ViewMode.FeedContent else feedSettings(article.feedId).viewMode
        return when (viewMode) {
            ViewMode.FeedContent -> ArticlePage.FeedContent(prepareArticleView(article))
            ViewMode.ReaderView -> ArticlePage.ReaderView(checkNotNull(originalPageUrl(article)))
            ViewMode.OriginalPage -> ArticlePage.OriginalPage(checkNotNull(originalPageUrl(article)))
        }
    }

    /** The Reader View of [article] from its Original Page at [pageUrl], as [articlePage] gave it. */
    fun readerView(article: Article, pageUrl: String): ReaderViewSession =
        ReaderViewSession(pageUrl, { content -> articleViewDocument(article, feedSettings(article.feedId), dateFormat, content) }, clock)

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
    suspend fun setCategoryColour(category: String, colour: CategoryColour?) = changeLock.withLock {
        val colours = if (colour == null) chosenCategoryColours - category else chosenCategoryColours + (category to colour)
        withContext(Dispatchers.IO) { categoryColourStore.save(colours) }
        chosenCategoryColours = colours
    }

    /** Replaces the Reading Order: the reader's chosen sequence of Categories, by name. */
    suspend fun setReadingOrder(order: List<String>) = changeLock.withLock {
        withContext(Dispatchers.IO) { readingOrderStore.save(order) }
        readingOrder = order
        rebuildSections()
    }

    /**
     * The link to [article]'s Original Page, for showing, opening and sharing it, [overHttps]; null if the Feed
     * gives none.
     */
    suspend fun originalPageUrl(article: Article): String? {
        val link = article.url ?: return null
        return overHttps(withContext(Dispatchers.IO) { followIt.unwrap(link) })
    }

    /** The Feed Settings of the Feed [feedId]; defaults for a Feed the reader has not changed. */
    fun feedSettings(feedId: String): FeedSettings = feedSettings[feedId] ?: FeedSettings()

    /** Replaces the Feed Settings of the Feed [feedId]; the next prepared Article View follows them. */
    suspend fun setFeedSettings(feedId: String, settings: FeedSettings) = changeLock.withLock {
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

/**
 * [link], if over plain http, over https instead. Android refuses to load pages over plain http, also in the in-app
 * browser; Dinosaur Comics, for one, links its comics over http, while its site answers over https as well.
 */
fun overHttps(link: String): String = PLAIN_HTTP_LINK.replace(link, "https://")

private val PLAIN_HTTP_LINK = Regex("""^http://""", RegexOption.IGNORE_CASE)
