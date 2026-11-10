package io.github.causaprima.freshreader.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.jsoup.parser.Parser
import java.io.IOException

internal sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>
    data class Failure(val outcome: Outcome) : ApiResult<Nothing>
}

/** Client for FreshRSS's Google Reader API (`/api/greader.php`). */
internal class GoogleReaderApi private constructor(private val http: OkHttpClient, private val apiBase: HttpUrl) {

    private var auth: String? = null
    /** The action token `edit-tag` requires, fetched once per session. */
    private var actionToken: String? = null

    suspend fun logIn(username: String, apiPassword: String): Outcome {
        val request = Request.Builder()
            .url(apiBase.newBuilder().addPathSegments("accounts/ClientLogin").build())
            .post(FormBody.Builder().add("Email", username).add("Passwd", apiPassword).build())
            .build()
        return when (val result = execute(request)) {
            is ApiResult.Failure -> result.outcome
            is ApiResult.Success -> {
                auth = result.value.lineSequence()
                    .firstOrNull { it.startsWith("Auth=") }
                    ?.removePrefix("Auth=")
                if (auth == null) Outcome.ServerError(200) else Outcome.Ok
            }
        }
    }

    /** Each Feed's Category and favicon, keyed by Feed id (`feed/<n>`); Feeds without a Category are left out. */
    suspend fun feeds(): ApiResult<Map<String, FeedInfo>> {
        val url = apiBase.newBuilder()
            .addPathSegments("reader/api/0/subscription/list")
            .addQueryParameter("output", "json")
            .build()
        return when (val result = execute(authorized(url))) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> try {
                ApiResult.Success(
                    json.decodeFromString<SubscriptionList>(result.value).subscriptions
                        .mapNotNull { s -> s.categories.firstOrNull()?.label?.let { s.id to FeedInfo(it, s.iconUrl, s.htmlUrl) } }
                        .toMap(),
                )
            } catch (e: SerializationException) {
                ApiResult.Failure(Outcome.ServerError(200))
            }
        }
    }

    /** All unread Articles, oldest crawled first, following `continuation` until the last page. */
    suspend fun unreadArticles(): ApiResult<List<Article>> {
        val articles = mutableListOf<Article>()
        val seenContinuations = mutableSetOf<String>()
        var continuation: String? = null
        do {
            val url = apiBase.newBuilder()
                .addPathSegments("reader/api/0/stream/contents/user/-/state/com.google/reading-list")
                .addQueryParameter("xt", "user/-/state/com.google/read")
                .addQueryParameter("r", "o")
                .addQueryParameter("n", PAGE_SIZE.toString())
                .apply { continuation?.let { addQueryParameter("c", it) } }
                .build()
            val page = when (val result = execute(authorized(url))) {
                is ApiResult.Failure -> return result
                is ApiResult.Success -> try {
                    json.decodeFromString<StreamContents>(result.value)
                } catch (e: SerializationException) {
                    return ApiResult.Failure(Outcome.ServerError(200))
                }
            }
            articles += page.items.map { it.toArticle() }
            continuation = page.continuation?.takeIf { page.items.isNotEmpty() && seenContinuations.add(it) }
        } while (continuation != null)
        return ApiResult.Success(articles)
    }

    /** Marks the Articles with these ids as read on the Server. */
    suspend fun markRead(articleIds: List<String>): Outcome {
        val token = actionToken ?: when (val result = execute(authorized(endpoint("token")))) {
            is ApiResult.Failure -> return result.outcome
            is ApiResult.Success -> result.value.trim().also { actionToken = it }
        }
        val form = FormBody.Builder()
            .add("T", token)
            .add("a", READ_STATE)
            .apply { articleIds.forEach { add("i", it) } }
            .build()
        return when (val result = execute(authorized(endpoint("edit-tag"), form))) {
            is ApiResult.Failure -> result.outcome
            is ApiResult.Success -> Outcome.Ok
        }
    }

    private fun endpoint(name: String) = apiBase.newBuilder().addPathSegments("reader/api/0/$name").build()

    private fun authorized(url: HttpUrl, form: RequestBody? = null) = Request.Builder()
        .url(url)
        .header("Authorization", "GoogleLogin auth=$auth")
        .apply { if (form != null) post(form) }
        .build()

    private suspend fun execute(request: Request): ApiResult<String> = withContext(Dispatchers.IO) {
        try {
            http.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> ApiResult.Success(response.body.string())
                    response.code == 401 || response.code == 400 && request.isLogin() ->
                        ApiResult.Failure(Outcome.WrongCredentials)
                    else -> ApiResult.Failure(Outcome.ServerError(response.code))
                }
            }
        } catch (e: IOException) {
            ApiResult.Failure(Outcome.Unreachable(e.message ?: e.javaClass.simpleName))
        }
    }

    private fun Request.isLogin() = url.encodedPath.endsWith("/accounts/ClientLogin")

    @Serializable
    private class StreamContents(val items: List<Item> = emptyList(), val continuation: String? = null)

    @Serializable
    private class Item(
        val id: String,
        val title: String = "",
        val published: Long = 0,
        val alternate: List<Link> = emptyList(),
        val origin: Origin? = null,
        /** FreshRSS's "compat" mode, which it always uses for streams, puts the Feed Content here. */
        val summary: Content? = null,
        val content: Content? = null,
    ) {
        fun toArticle() = Article(
            id = id,
            title = plainText(title),
            feedId = origin?.streamId.orEmpty(),
            feedTitle = plainText(origin?.title.orEmpty(), FEED_NAME_STAND_INS),
            published = published,
            url = alternate.firstOrNull()?.href,
            content = summary?.content ?: content?.content.orEmpty(),
        )
    }

    @Serializable
    private class Link(val href: String)

    @Serializable
    private class Content(val content: String = "")

    @Serializable
    private class Origin(val streamId: String? = null, val title: String? = null)

    @Serializable
    private class SubscriptionList(val subscriptions: List<Subscription> = emptyList())

    @Serializable
    private class Subscription(
        val id: String,
        val categories: List<Label> = emptyList(),
        val iconUrl: String? = null,
        val htmlUrl: String? = null,
    )

    @Serializable
    private class Label(val label: String? = null)

    /**
     * A Feed as `subscription/list` describes it. [iconUrl]: FreshRSS's `f.php` link to its favicon.
     * [site]: the Feed's web site.
     */
    class FeedInfo(val category: String, val iconUrl: String?, val site: String?)

    companion object {
        /**
         * [text] as the reader should see it. FreshRSS hands out `&`, `<` and `>` as their fullwidth forms, in
         * Feed names also [FEED_NAME_STAND_INS]; and HTML entities a Feed escaped itself (as Der Postillon does
         * with quotes) stay in its titles.
         */
        private fun plainText(text: String, standIns: Map<Char, Char> = STAND_INS): String {
            val restored = buildString(text.length) { text.forEach { append(standIns[it] ?: it) } }
            return Parser.unescapeEntities(restored, false)
        }

        private val STAND_INS = mapOf('＆' to '&', '＜' to '<', '＞' to '>')
        private val FEED_NAME_STAND_INS =
            STAND_INS + mapOf('，' to ',', '／' to '/', '？' to '?', '＼' to '\\', '；' to ';')

        /** Accepts the FreshRSS root URL or its `/api/greader.php` URL; null if it is not an http(s) URL. */
        fun forServer(http: OkHttpClient, serverUrl: String): GoogleReaderApi? {
            val base = serverUrl.trim().trimEnd('/').let {
                if (it.endsWith("/api/greader.php")) it else "$it/api/greader.php"
            }.toHttpUrlOrNull() ?: return null
            return GoogleReaderApi(http, base)
        }

        private const val PAGE_SIZE = 1000
        private const val READ_STATE = "user/-/state/com.google/read"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
