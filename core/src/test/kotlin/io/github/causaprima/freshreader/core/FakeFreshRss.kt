package io.github.causaprima.freshreader.core

import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A fake FreshRSS Server speaking the Google Reader API over HTTP.
 *
 * Mirrors the behaviour of FreshRSS's `p/api/greader.php` (edge): ClientLogin answers 401 on a wrong
 * API password, API calls without a valid `GoogleLogin auth=` header answer 401, and `stream/contents`
 * only sends a `continuation` when the page was full, and `subscription/list` requires `output=json`.
 */
class FakeFreshRss : Dispatcher(), AutoCloseable {
    /** [listed] = false: the Feed is missing from `subscription/list`, e.g. subscribed between two requests. */
    data class FakeFeed(val id: Long, val title: String, val category: String, val listed: Boolean = true)

    data class FakeArticle(
        val id: Long,
        val title: String,
        val feedTitle: String,
        val published: Long,
        val read: Boolean = false,
    )

    var username = "reader"
    var apiPassword = "api-secret"
    val authToken get() = "$username/0123456789abcdef"

    /** Upper bound for items per `stream/contents` page, regardless of the client's `n`. */
    var maxPageSize = 1000

    /** When false, every connection is dropped as if the Server were unreachable. */
    var reachable = true

    /** When true, the unread stream answers 200 with a body that is not Google Reader JSON. */
    var garbledStream = false

    val feeds = mutableListOf<FakeFeed>()
    val articles = mutableListOf<FakeArticle>()
    val requests = CopyOnWriteArrayList<RecordedRequest>()

    private val server = MockWebServer()

    /** The URL the reader would type in: the root of the FreshRSS installation. */
    val url: String get() = server.url("/").toString().trimEnd('/')

    fun start(port: Int = 0): FakeFreshRss {
        server.dispatcher = this
        server.start(port)
        return this
    }

    override fun close() = server.close()

    fun addFeed(title: String, category: String, listed: Boolean = true): FakeFeed =
        FakeFeed(feeds.size + 1L, title, category, listed).also { feeds += it }

    /** Adds an Article; a Feed not added before is created in the Category "Uncategorized". */
    fun addArticle(title: String, feedTitle: String = "Feed", published: Long, read: Boolean = false) {
        if (feeds.none { it.title == feedTitle }) addFeed(feedTitle, "Uncategorized")
        articles += FakeArticle(articles.size + 1L, title, feedTitle, published, read)
    }

    private fun feedOf(article: FakeArticle) = feeds.first { it.title == article.feedTitle }

    /**
     * Drops the connection. MockWebServer applies request-start effects from [peek] on new connections;
     * on a reused keep-alive connection the response-start effect closes the socket instead of answering.
     */
    private val dropConnection = MockResponse.Builder()
        .onRequestStart(SocketEffect.CloseSocket())
        .onResponseStart(SocketEffect.CloseSocket())
        .build()

    override fun peek(): MockResponse = if (reachable) super.peek() else dropConnection

    override fun dispatch(request: RecordedRequest): MockResponse {
        requests += request
        if (!reachable) return dropConnection
        val path = request.url.encodedPath
        return when {
            path == "/api/greader.php/accounts/ClientLogin" -> clientLogin(request)
            !path.startsWith("/api/greader.php/reader/api/0/") -> text(404, "Not found")
            request.headers["Authorization"] != "GoogleLogin auth=$authToken" -> text(401, "Unauthorized!")
            path == "/api/greader.php/reader/api/0/stream/contents/user/-/state/com.google/reading-list" ->
                streamContents(request)
            path == "/api/greader.php/reader/api/0/subscription/list" -> subscriptionList(request)
            else -> text(400, "Bad Request!")
        }
    }

    private fun clientLogin(request: RecordedRequest): MockResponse {
        val form = request.body?.utf8().orEmpty().split('&').filter { it.isNotEmpty() }.associate {
            val (k, v) = it.split('=', limit = 2)
            URLDecoder.decode(k, Charsets.UTF_8) to URLDecoder.decode(v, Charsets.UTF_8)
        }
        if (request.method != "POST") return text(400, "Bad Request!")
        if (form["Email"] != username || form["Passwd"] != apiPassword) return text(401, "Unauthorized!")
        return text(200, "SID=$authToken\nLSID=null\nAuth=$authToken\n")
    }

    private fun streamContents(request: RecordedRequest): MockResponse {
        if (garbledStream) return text(200, "<html>Maintenance</html>")
        val url = request.url
        val excludeRead = url.queryParameter("xt") == "user/-/state/com.google/read"
        val ascending = url.queryParameter("r") == "o"
        val count = minOf(url.queryParameter("n")?.toInt() ?: 20, maxPageSize)
        val continuation = url.queryParameter("c")?.toLong()

        val ordered = articles
            .filter { !(excludeRead && it.read) }
            .let { list -> if (ascending) list.sortedBy { it.id } else list.sortedByDescending { it.id } }
        val remaining = if (continuation == null) ordered
        else ordered.dropWhile { it.id != continuation }.drop(1)
        val page = remaining.take(count)

        val json = buildJsonObject {
            put("id", "user/-/state/com.google/reading-list")
            put("updated", 1_800_000_000)
            putJsonArray("items") {
                page.forEach { a ->
                    addJsonObject {
                        put("id", "tag:google.com,2005:reader/item/%016x".format(a.id))
                        put("crawlTimeMsec", "${a.id}000")
                        put("published", a.published)
                        put("title", a.title)
                        putJsonArray("canonical") { addJsonObject { put("href", "https://example.org/${a.id}") } }
                        putJsonArray("alternate") {
                            addJsonObject { put("href", "https://example.org/${a.id}"); put("type", "text/html") }
                        }
                        putJsonArray("categories") {
                            add("user/-/state/com.google/reading-list")
                            add("user/-/label/${feedOf(a).category}")
                        }
                        putJsonObject("origin") {
                            put("streamId", "feed/${feedOf(a).id}")
                            put("htmlUrl", "https://example.org/")
                            put("title", a.feedTitle)
                        }
                        putJsonObject("content") { put("content", "<p>${a.title}</p>") }
                    }
                }
            }
            if (page.size >= count && page.isNotEmpty()) put("continuation", page.last().id.toString())
        }
        return MockResponse.Builder()
            .code(200)
            .setHeader("Content-Type", "application/json; charset=UTF-8")
            .body(json.toString())
            .build()
    }

    private fun subscriptionList(request: RecordedRequest): MockResponse {
        if (request.url.queryParameter("output") != "json") return text(501, "Not Implemented!")
        val json = buildJsonObject {
            putJsonArray("subscriptions") {
                feeds.filter { it.listed }.forEach { f ->
                    addJsonObject {
                        put("id", "feed/${f.id}")
                        put("title", f.title)
                        putJsonArray("categories") {
                            addJsonObject { put("id", "user/-/label/${f.category}"); put("label", f.category) }
                        }
                        put("url", "https://example.org/feed/${f.id}.xml")
                        put("htmlUrl", "https://example.org/")
                        put("iconUrl", "https://freshrss.example.org/f.php?${f.id}")
                    }
                }
            }
        }
        return MockResponse.Builder()
            .code(200)
            .setHeader("Content-Type", "application/json; charset=UTF-8")
            .body(json.toString())
            .build()
    }

    private fun text(code: Int, body: String) = MockResponse.Builder()
        .code(code)
        .setHeader("Content-Type", "text/plain; charset=UTF-8")
        .body(body)
        .build()
}
