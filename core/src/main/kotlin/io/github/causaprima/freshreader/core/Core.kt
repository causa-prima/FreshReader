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
    val feedTitle: String,
    /** Publication time in epoch seconds. */
    val published: Long,
    val url: String?,
)

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

    /** The current list snapshot; replaced only by [refresh]. */
    var articles: List<Article> = emptyList()
        private set

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
        return when (val result = api.unreadArticles()) {
            is ApiResult.Success -> {
                articles = result.value.sortedBy { it.published }
                Outcome.Ok
            }
            is ApiResult.Failure -> result.outcome
        }
    }
}
