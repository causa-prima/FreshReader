package io.github.causaprima.freshreader

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.causaprima.freshreader.core.Article
import io.github.causaprima.freshreader.core.Core
import io.github.causaprima.freshreader.core.Outcome
import io.github.causaprima.freshreader.core.Section
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
    data class Articles(val sections: List<Section>) : Screen
    data class ReadingOrder(val categories: List<String>) : Screen
    /** [document]: the Article prepared by the Core, an HTML document with header and Feed Content. */
    data class ArticleView(val article: Article, val document: String) : Screen
}

/** Translates Core Outcomes into Screens. Holds no business rules. */
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val core = Core(
        storeDir = File(application.filesDir, "core"),
        secretBox = KeystoreSecretBox(),
    )

    private val _screen = MutableStateFlow<Screen>(Screen.Loading)
    val screen: StateFlow<Screen> = _screen

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
        _screen.value = Screen.ArticleView(article, core.prepareArticleView(article))
    }

    fun closeArticle() {
        _screen.value = Screen.Articles(core.sections)
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

    private suspend fun refresh() {
        _screen.value = when (val outcome = withContext(Dispatchers.IO) { core.refresh() }) {
            Outcome.Ok -> Screen.Articles(core.sections)
            Outcome.WrongCredentials, Outcome.InvalidServerUrl -> Screen.Login(error = outcome)
            is Outcome.Unreachable, is Outcome.ServerError -> Screen.Failed(outcome)
        }
    }
}
