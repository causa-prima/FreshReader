package io.github.causaprima.freshreader.core

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Stands in for `api.follow.it`: a story link answers with the redirect follow.it gives, captured from the real
 * service. [client] sends every request for `api.follow.it` here instead.
 */
class FakeFollowIt : Dispatcher(), AutoCloseable {
    private val server = MockWebServer().also { it.dispatcher = this }

    /** Story path, e.g. `/track-rss-story-click/v3/…`, to the `Location` follow.it redirects it to. */
    private val redirects = mutableMapOf<String, String>()

    /** The paths requested from follow.it. */
    val requests = CopyOnWriteArrayList<String>()

    fun start(): FakeFollowIt = apply { server.start() }

    override fun close() = server.close()

    fun redirect(storyPath: String, location: String) {
        redirects[storyPath] = location
    }

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.url.encodedPath
        requests += path
        val location = redirects[path] ?: return MockResponse.Builder().code(404).build()
        return MockResponse.Builder().code(302).addHeader("Location", location).body("Redirecting to $location").build()
    }

    /** An HTTP client that reaches this fake whenever it is asked for `api.follow.it`. */
    fun client(): OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        if (request.url.host != "api.follow.it") return@addInterceptor chain.proceed(request)
        val rerouted = request.url.newBuilder().scheme("http").host(server.hostName).port(server.port).build()
        chain.proceed(request.newBuilder().url(rerouted).build())
    }.build()
}
