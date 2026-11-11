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
import io.github.causaprima.freshreader.core.CategoryColour
import io.github.causaprima.freshreader.core.FeedSettings
import io.github.causaprima.freshreader.core.Outcome
import io.github.causaprima.freshreader.core.PageLoad
import io.github.causaprima.freshreader.core.ReaderView
import io.github.causaprima.freshreader.core.Section
import io.github.causaprima.freshreader.core.SyncStatus
import io.github.causaprima.freshreader.core.ViewMode
import kotlinx.coroutines.Dispatchers
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
     * in the Article View. [fromTop]: the list starts again at its top, as the Reading Order has changed: the reader
     * would otherwise go on reading among Categories now placed above, which opening an Article marks as read.
     * [refreshError]: why the last pull-to-refresh failed, shown once; the list stays.
     */
    data class Articles(
        val sections: List<Section>,
        val focus: String? = null,
        val fromTop: Boolean = false,
        val refreshing: Boolean = false,
        val refreshError: Outcome? = null,
    ) : Screen
    /**
     * The settings: the current [account] and the Reading Order of the [categories], with each one's Category
     * Colour in [colours] and the colours the reader chose in [chosenColours]; the others are automatic.
     */
    data class Settings(
        val account: Account?,
        val categories: List<String>,
        val colours: Map<String, CategoryColour> = emptyMap(),
        val chosenColours: Map<String, CategoryColour> = emptyMap(),
    ) : Screen
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

    /** The Reading Order as the settings were opened with it. */
    private var readingOrderBefore: List<String> = emptyList()

    init {
        viewModelScope.launch {
            if (withContext(Dispatchers.IO) { core.hasStoredCredentials }) refresh() else _screen.value = Screen.Login()
        }
        // The list shows the snapshot as it changes, e.g. Articles greyed out once their read rule is applied.
        viewModelScope.launch {
            core.snapshot.collect { sections ->
                val list = _screen.value as? Screen.Articles ?: return@collect
                _screen.value = list.copy(sections = sections)
            }
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

    /** Opens [article] from the list; not while a refresh is about to replace the list. */
    fun openArticle(article: Article) {
        val list = _screen.value as? Screen.Articles ?: return
        if (list.refreshing) return
        val articles = core.articles
        val position = articles.indexOfFirst { it.id == article.id }
        if (position < 0) return
        _screen.value = Screen.ArticleView(articles, position)
        viewModelScope.launch { core.openArticle(article) }
    }

    /** The reader swiped to the Article at [position]; arriving there counts as opening it. */
    fun showArticle(position: Int) {
        val view = _screen.value as? Screen.ArticleView ?: return
        _screen.value = view.copy(position = position)
        viewModelScope.launch { core.openArticle(view.articles[position]) }
    }

    /** Pull-to-refresh: replaces the snapshot. If the Server cannot be reached, the current list stays. */
    fun pullToRefresh() {
        val list = _screen.value as? Screen.Articles ?: return
        _screen.value = list.copy(focus = null, refreshing = true, refreshError = null)
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) { core.refresh() }
            val next = screenAfter(outcome) { Screen.Articles(core.sections, refreshError = it) }
            if (_screen.value is Screen.Articles) _screen.value = next
        }
    }

    fun dismissRefreshError() {
        val list = _screen.value as? Screen.Articles ?: return
        _screen.value = list.copy(refreshError = null)
    }

    /** Marks every not-yet-read Article of the snapshot as read; not while a refresh is about to replace it. */
    fun markAllAsRead() {
        val list = _screen.value as? Screen.Articles ?: return
        if (list.refreshing) return
        _screen.value = list.copy(focus = null)
        viewModelScope.launch { core.markAllAsRead() }
    }

    /** Decoded favicons by Feed id, so that scrolling does not decode them again. */
    private val favicons = ConcurrentHashMap<String, ImageBitmap>()

    /** The favicon of [article]'s Feed, small enough for an Article's card in the list; null if there is none or it cannot be shown. */
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
        viewModelScope.launch {
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
        val view = _screen.value as? Screen.ArticleView ?: return
        _screen.value = Screen.Articles(core.sections, focus = view.article.id)
    }

    fun openSettings() {
        viewModelScope.launch {
            readingOrderBefore = core.categories
            _screen.value = withColours(Screen.Settings(withContext(Dispatchers.IO) { core.account }, core.categories))
        }
    }

    /** [settings] with the Category Colours as they are now; automatic ones follow the Reading Order. */
    private fun withColours(settings: Screen.Settings) = settings.copy(
        colours = settings.categories.associateWith(core::categoryColour),
        chosenColours = settings.categories.mapNotNull { c -> core.chosenCategoryColour(c)?.let { c to it } }.toMap(),
    )

    /** Shows the settings again with the Category Colours as they are now, if they are still open. */
    private fun showColours() {
        val settings = _screen.value as? Screen.Settings ?: return
        _screen.value = withColours(settings)
    }

    /** The Category Colour of [category] in the list. */
    fun categoryColour(category: String): CategoryColour = core.categoryColour(category)

    /** Chooses [category]'s Category Colour; null lets it be picked automatically. */
    fun setCategoryColour(category: String, colour: CategoryColour?) {
        viewModelScope.launch {
            core.setCategoryColour(category, colour)
            showColours()
        }
    }

    fun setReadingOrder(order: List<String>) {
        val settings = _screen.value as? Screen.Settings ?: return
        _screen.value = settings.copy(categories = order)
        viewModelScope.launch {
            core.setReadingOrder(order)
            showColours()
        }
    }

    /** Back to the list; it shows the Reading Order once stored, from the top if it was changed. */
    fun closeSettings() {
        val settings = _screen.value as? Screen.Settings ?: return
        _screen.value = Screen.Articles(core.sections, fromTop = settings.categories != readingOrderBefore)
    }

    override fun onCleared() = core.close()

    private suspend fun refresh() {
        val outcome = withContext(Dispatchers.IO) { core.refresh() }
        _screen.value = screenAfter(outcome) { Screen.Failed(it) }
    }

    /** The screen after a refresh with [outcome]: the list, the login, or [unreachable] if the Server failed. */
    private suspend fun screenAfter(outcome: Outcome, unreachable: (Outcome) -> Screen): Screen = when (outcome) {
        Outcome.Ok -> Screen.Articles(core.sections)
        Outcome.WrongCredentials, Outcome.InvalidServerUrl ->
            Screen.Login(error = outcome, account = withContext(Dispatchers.IO) { core.account })
        is Outcome.Unreachable, is Outcome.ServerError -> unreachable(outcome)
    }
}
