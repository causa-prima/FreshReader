package io.github.causaprima.freshreader.core

import okhttp3.OkHttpClient
import java.io.File

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
)

/** One Category's part of the list snapshot. */
data class Section(val category: String, val articles: List<Article>) {
    val unreadCount: Int get() = articles.size
}

/**
 * FreshReader's UI-independent core: the single seam the UI talks to.
 */
class Core(
    storeDir: File,
    secretBox: SecretBox,
    httpClient: OkHttpClient = OkHttpClient(),
) {
    private val http = httpClient
    private val credentialStore = CredentialStore(storeDir, secretBox)
    private var api: GoogleReaderApi? = null

    /** The current list snapshot, sectioned by Category; replaced only by [refresh]. */
    var sections: List<Section> = emptyList()
        private set

    /** All Articles of the snapshot in display order, across sections. */
    val articles: List<Article> get() = sections.flatMap { it.articles }

    /** Whether credentials from an earlier successful login are stored on the device. */
    val hasStoredCredentials: Boolean get() = credentialStore.load() != null

    /** Logs in with the API password; on success the credentials are stored encrypted. */
    suspend fun logIn(serverUrl: String, username: String, apiPassword: String): Outcome {
        val candidate = GoogleReaderApi.forServer(http, serverUrl) ?: return Outcome.InvalidServerUrl
        val outcome = candidate.logIn(username, apiPassword)
        if (outcome == Outcome.Ok) {
            credentialStore.save(Credentials(serverUrl, username, apiPassword))
            api = candidate
        }
        return outcome
    }

    /** Replaces the list snapshot with all unread Articles, logging in with stored credentials if needed. */
    suspend fun refresh(): Outcome {
        val api = api ?: run {
            val stored = credentialStore.load() ?: return Outcome.WrongCredentials
            val outcome = logIn(stored.serverUrl, stored.username, stored.apiPassword)
            if (outcome != Outcome.Ok) return outcome
            api!!
        }
        val categories = when (val result = api.feedCategories()) {
            is ApiResult.Success -> result.value
            is ApiResult.Failure -> return result.outcome
        }
        val unread = when (val result = api.unreadArticles()) {
            is ApiResult.Success -> result.value
            is ApiResult.Failure -> return result.outcome
        }
        sections = buildSections(unread, categories)
        return Outcome.Ok
    }
}
