package io.github.causaprima.freshreader

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.causaprima.freshreader.core.Article
import io.github.causaprima.freshreader.core.Core
import io.github.causaprima.freshreader.core.Outcome
import io.github.causaprima.freshreader.core.Section
import io.github.causaprima.freshreader.core.SyncStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface Screen {
    data object Loading : Screen
    data class Login(val error: Outcome? = null, val busy: Boolean = false) : Screen
    data class Failed(val outcome: Outcome) : Screen
    /** [focus]: id of the Article the list should bring into view, e.g. the last one shown in the Article View. */
    data class Articles(val sections: List<Section>, val focus: String? = null) : Screen
    data class ReadingOrder(val categories: List<String>) : Screen
    /** The Article View, swipeable over the list snapshot's [articles]; [position] is the Article shown. */
    data class ArticleView(val articles: List<Article>, val position: Int) : Screen {
        val article: Article get() = articles[position]
    }
}

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

    init {
        viewModelScope.launch {
            if (withContext(Dispatchers.IO) { core.hasStoredCredentials }) refresh() else _screen.value = Screen.Login()
        }
    }

    fun logIn(serverUrl: String, username: String, apiPassword: String) {
        _screen.value = Screen.Login(busy = true)
        viewModelScope.launch {
            when (val outcome = withContext(Dispatchers.IO) { core.logIn(serverUrl, username, apiPassword) }) {
                Outcome.Ok -> refresh()
                else -> _screen.value = Screen.Login(error = outcome)
            }
        }
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

    /** The Article prepared by the Core: an HTML document with header and Feed Content. */
    fun articleDocument(article: Article): String = core.prepareArticleView(article)

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

    fun openReadingOrder() {
        _screen.value = Screen.ReadingOrder(core.categories)
    }

    fun setReadingOrder(order: List<String>) {
        _screen.value = Screen.ReadingOrder(order)
        val previous = readingOrderChange
        readingOrderChange = viewModelScope.launch {
            previous?.join()
            core.setReadingOrder(order)
        }
    }

    fun closeReadingOrder() {
        viewModelScope.launch {
            readingOrderChange?.join()
            _screen.value = Screen.Articles(core.sections)
        }
    }

    override fun onCleared() = core.close()

    private suspend fun refresh() {
        _screen.value = when (val outcome = withContext(Dispatchers.IO) { core.refresh() }) {
            Outcome.Ok -> Screen.Articles(core.sections)
            Outcome.WrongCredentials, Outcome.InvalidServerUrl -> Screen.Login(error = outcome)
            is Outcome.Unreachable, is Outcome.ServerError -> Screen.Failed(outcome)
        }
    }
}
