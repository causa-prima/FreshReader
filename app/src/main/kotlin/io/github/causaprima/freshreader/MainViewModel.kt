package io.github.causaprima.freshreader

import android.app.Application
import android.graphics.ImageDecoder
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.causaprima.freshreader.core.Account
import io.github.causaprima.freshreader.core.Article
import io.github.causaprima.freshreader.core.Core
import io.github.causaprima.freshreader.core.FeedSettings
import io.github.causaprima.freshreader.core.Outcome
import io.github.causaprima.freshreader.core.PageLoad
import io.github.causaprima.freshreader.core.ReaderView
import io.github.causaprima.freshreader.core.Section
import io.github.causaprima.freshreader.core.SyncStatus
import io.github.causaprima.freshreader.core.ViewMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

sealed interface Screen {
    data object Loading : Screen
    /**
     * The login. [account]: the current Server and user, offered when changing them. [back]: the screen that going
     * back returns to, unchanged; null on first start, when there is none.
     */
    data class Login(
        val error: Outcome? = null,
        val busy: Boolean = false,
        val account: Account? = null,
        val back: Screen? = null,
    ) : Screen
    data class Failed(val outcome: Outcome) : Screen
    /**
     * The list snapshot. [focus]: id of the Article the list should bring into view, e.g. the last one shown
     * in the Article View. [refreshError]: why the last pull-to-refresh failed, shown once; the list stays.
     */
    data class Articles(
        val sections: List<Section>,
        val focus: String? = null,
        val refreshing: Boolean = false,
        val refreshError: Outcome? = null,
    ) : Screen
    /** The settings: the current [account] and the Reading Order of the [categories]. */
    data class Settings(val account: Account?, val categories: List<String>) : Screen
    /**
     * The Article View, swipeable over the list snapshot's [articles]; [position] is the Article shown.
     * [feedSettings]: the Feed Settings sheet, while it is open. [revision] grows whenever Feed Settings
     * change, so that the shown documents are prepared again.
     */
    data class ArticleView(
        val articles: List<Article>,
        val position: Int,
        val feedSettings: FeedSettingsSheet? = null,
        val revision: Int = 0,
    ) : Screen {
        val article: Article get() = articles[position]
    }
}

/** What one page of the Article View shows. */
sealed interface ArticlePage {
    /** The Core's prepared document: header and Feed Content. */
    data class FeedContent(val document: String) : ArticlePage

    /** The Article's web page reduced to its main content, extracted from the page in the in-app browser. */
    data object ReaderView : ArticlePage

    /** The Article's web page, in the in-app browser. */
    data object OriginalPage : ArticlePage
}

/** The Feed Settings sheet for one Feed, showing its current [settings]. */
data class FeedSettingsSheet(val feedId: String, val feedTitle: String, val settings: FeedSettings)

/** Decodes a favicon, scaled down to at most [FAVICON_PIXELS]; null for formats Android cannot decode, e.g. SVG. */
private fun decodeFavicon(bytes: ByteArray): ImageBitmap? = try {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
        val scale = FAVICON_PIXELS.toFloat() / maxOf(info.size.width, info.size.height)
        if (scale < 1) decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
    }.asImageBitmap()
} catch (_: IOException) {
    null
}

private const val FAVICON_PIXELS = 96

/** Translates Core Outcomes into Screens. Holds no business rules. */
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val core = Core(
        storeDir = File(application.filesDir, "core"),
        secretBox = KeystoreSecretBox(),
    )

    private val _screen = MutableStateFlow<Screen>(Screen.Loading)
    val screen: StateFlow<Screen> = _screen

    /** Whether the read marks have reached the Server; a permanent failure shows as a hint. */
    val syncStatus: StateFlow<SyncStatus> = core.syncStatus

    /** The read rule of the last opened Article, applied while the Article View is shown; applied in order. */
    private var opening: Job? = null

    /** The last Reading Order change still being stored; changes are applied one after another. */
    private var readingOrderChange: Job? = null

    /** The last Feed Settings change still being stored; changes are applied one after another. */
    private var feedSettingsChange: Job? = null

    init {
        viewModelScope.launch {
            if (withContext(Dispatchers.IO) { core.hasStoredCredentials }) refresh() else _screen.value = Screen.Login()
        }
    }

    fun logIn(serverUrl: String, username: String, apiPassword: String) {
        val login = _screen.value as? Screen.Login ?: return
        _screen.value = login.copy(error = null, busy = true)
        viewModelScope.launch {
            when (val outcome = withContext(Dispatchers.IO) { core.logIn(serverUrl, username, apiPassword) }) {
                Outcome.Ok -> {
                    favicons.clear() // another Server may use the same Feed ids for other Feeds
                    _screen.value = Screen.Loading
                    refresh()
                }
                else -> _screen.value = login.copy(error = outcome, busy = false)
            }
        }
    }

    /** Opens the login to change the Server or user, offering the current ones; going back changes nothing. */
    fun changeServer() {
        val current = _screen.value
        viewModelScope.launch {
            _screen.value = Screen.Login(account = withContext(Dispatchers.IO) { core.account }, back = current)
        }
    }

    /** Leaves the login opened by [changeServer] without changing anything. */
    fun cancelLogin() {
        val login = _screen.value as? Screen.Login ?: return
        if (login.busy) return
        login.back?.let { _screen.value = it }
    }

    fun retry() {
        _screen.value = Screen.Loading
        viewModelScope.launch { refresh() }
    }

    fun openArticle(article: Article) {
        val articles = core.articles
        val position = articles.indexOfFirst { it.id == article.id }
        if (position < 0) return
        _screen.value = Screen.ArticleView(articles, position)
        applyReadRule(position)
    }

    /** The reader swiped to the Article at [position]; arriving there counts as opening it. */
    fun showArticle(position: Int) {
        val view = _screen.value as? Screen.ArticleView ?: return
        _screen.value = view.copy(position = position)
        applyReadRule(position)
    }

    /** Pull-to-refresh: replaces the snapshot. If the Server cannot be reached, the current list stays. */
    fun pullToRefresh() {
        val list = _screen.value as? Screen.Articles ?: return
        _screen.value = list.copy(focus = null, refreshing = true, refreshError = null)
        viewModelScope.launch {
            opening?.join()
            _screen.value = when (val outcome = withContext(Dispatchers.IO) { core.refresh() }) {
                Outcome.Ok -> Screen.Articles(core.sections)
                Outcome.WrongCredentials, Outcome.InvalidServerUrl ->
                    Screen.Login(error = outcome, account = withContext(Dispatchers.IO) { core.account })
                is Outcome.Unreachable, is Outcome.ServerError ->
                    Screen.Articles(core.sections, refreshError = outcome)
            }
        }
    }

    fun dismissRefreshError() {
        val list = _screen.value as? Screen.Articles ?: return
        _screen.value = list.copy(refreshError = null)
    }

    /** Marks every not-yet-read Article of the snapshot as read. */
    fun markAllAsRead() {
        val previous = opening
        opening = viewModelScope.launch {
            previous?.join()
            core.markAllAsRead()
            val list = _screen.value as? Screen.Articles ?: return@launch
            _screen.value = list.copy(sections = core.sections, focus = null)
        }
    }

    /** Decoded favicons by Feed id, so that scrolling does not decode them again. */
    private val favicons = ConcurrentHashMap<String, ImageBitmap>()

    /** The favicon of [article]'s Feed, small enough for a list entry; null if there is none or it cannot be shown. */
    suspend fun favicon(article: Article): ImageBitmap? {
        favicons[article.feedId]?.let { return it }
        val bytes = core.favicon(article) ?: return null
        return withContext(Dispatchers.Default) { decodeFavicon(bytes) }?.also { favicons[article.feedId] = it }
    }

    /** What the Article View shows for [article], following its Feed's View Mode. */
    fun articlePage(article: Article): ArticlePage = when {
        article.url == null -> ArticlePage.FeedContent(core.prepareArticleView(article))
        else -> when (core.feedSettings(article.feedId).viewMode) {
            ViewMode.FeedContent -> ArticlePage.FeedContent(core.prepareArticleView(article))
            ViewMode.ReaderView -> ArticlePage.ReaderView
            ViewMode.OriginalPage -> ArticlePage.OriginalPage
        }
    }

    /** The script to run in a loaded Original Page for the Reader View. */
    val readerViewScript: String get() = core.readerViewScript

    /** What the Reader View shows of [article], from the script's [report] on the page loaded from [pageUrl] as [pageLoad]. */
    fun readerView(article: Article, pageUrl: String, pageLoad: PageLoad, report: String): ReaderView =
        core.readerView(article, pageUrl, pageLoad, report)

    /** Whether the Reader View, loading a page as text and layout only, lets a request through. */
    fun readerViewLoads(isPageItself: Boolean, accept: String?): Boolean = core.readerViewLoads(isPageItself, accept)

    /** The link to [article]'s Original Page, tracking links unwrapped; null if the Feed gives none. */
    suspend fun originalPageUrl(article: Article): String? = core.originalPageUrl(article)

    fun openFeedSettings() {
        val view = _screen.value as? Screen.ArticleView ?: return
        val feedId = view.article.feedId
        _screen.value = view.copy(feedSettings = FeedSettingsSheet(feedId, view.article.feedTitle, core.feedSettings(feedId)))
    }

    /** Changes the open sheet's Feed Settings; the Article View shows the change as soon as it is stored. */
    fun setFeedSettings(settings: FeedSettings) {
        val view = _screen.value as? Screen.ArticleView ?: return
        val sheet = view.feedSettings ?: return
        _screen.value = view.copy(feedSettings = sheet.copy(settings = settings))
        val previous = feedSettingsChange
        feedSettingsChange = viewModelScope.launch {
            previous?.join()
            core.setFeedSettings(sheet.feedId, settings)
            val current = _screen.value as? Screen.ArticleView ?: return@launch
            _screen.value = current.copy(revision = current.revision + 1)
        }
    }

    fun closeFeedSettings() {
        val view = _screen.value as? Screen.ArticleView ?: return
        _screen.value = view.copy(feedSettings = null)
    }

    fun closeArticle() {
        val focus = (_screen.value as? Screen.ArticleView)?.article?.id
        viewModelScope.launch {
            opening?.join()
            _screen.value = Screen.Articles(core.sections, focus)
        }
    }

    private fun applyReadRule(position: Int) {
        val previous = opening
        opening = viewModelScope.launch {
            previous?.join()
            core.openArticle(position)
        }
    }

    fun openSettings() {
        viewModelScope.launch {
            _screen.value = Screen.Settings(withContext(Dispatchers.IO) { core.account }, core.categories)
        }
    }

    fun setReadingOrder(order: List<String>) {
        val settings = _screen.value as? Screen.Settings ?: return
        _screen.value = settings.copy(categories = order)
        val previous = readingOrderChange
        readingOrderChange = viewModelScope.launch {
            previous?.join()
            core.setReadingOrder(order)
        }
    }

    fun closeSettings() {
        viewModelScope.launch {
            readingOrderChange?.join()
            _screen.value = Screen.Articles(core.sections)
        }
    }

    override fun onCleared() = core.close()

    private suspend fun refresh() {
        _screen.value = when (val outcome = withContext(Dispatchers.IO) { core.refresh() }) {
            Outcome.Ok -> Screen.Articles(core.sections)
            Outcome.WrongCredentials, Outcome.InvalidServerUrl ->
                Screen.Login(error = outcome, account = withContext(Dispatchers.IO) { core.account })
            is Outcome.Unreachable, is Outcome.ServerError -> Screen.Failed(outcome)
        }
    }
}
