package io.github.causaprima.freshreader.core

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Stands in for a Feed's web site: pages and files by path, each file with an ETag that a conditional request
 * (`If-None-Match`) is answered with 304 for, or, if [file] says so, only with a date (`Last-Modified`, answered with
 * 304 for `If-Modified-Since`).
 */
class FakeSite : Dispatcher(), AutoCloseable {
    private class File(val bytes: ByteArray, val contentType: String, val etag: String?, val lastModified: String? = null)

    private val server = MockWebServer().also { it.dispatcher = this }
    private val files = ConcurrentHashMap<String, File>()

    /** The paths requested with the status they were answered with, in order. */
    val responses = CopyOnWriteArrayList<Pair<String, Int>>()

    /** When false, every connection is dropped as if the site were unreachable. */
    @Volatile
    var reachable = true

    fun start(): FakeSite = apply { server.start() }

    override fun close() = server.close()

    fun url(path: String = "/"): String = server.url(path).toString()

    fun page(path: String, html: String) {
        files[path] = File(html.encodeToByteArray(), "text/html; charset=UTF-8", "\"page-${html.hashCode()}\"")
    }

    /** A file; [datedOnly] gives it no ETag but a `Last-Modified` date, as some sites do. */
    fun file(path: String, bytes: ByteArray, contentType: String, datedOnly: Boolean = false) {
        files[path] = if (datedOnly) File(bytes, contentType, etag = null, lastModified = "Tue, 10 Nov 2026 12:00:00 GMT")
        else File(bytes, contentType, "\"${bytes.contentHashCode()}\"")
    }

    fun remove(path: String) {
        files.remove(path)
    }

    override fun peek(): MockResponse = if (reachable) super.peek() else dropConnection

    override fun dispatch(request: RecordedRequest): MockResponse {
        if (!reachable) return dropConnection
        val path = request.url.encodedPath
        val file = files[path] ?: return answer(path, MockResponse.Builder().code(404).body("Not Found").build())
        val unchanged = file.etag != null && request.headers["If-None-Match"] == file.etag ||
            file.lastModified != null && request.headers["If-Modified-Since"] == file.lastModified
        if (unchanged) return answer(path, MockResponse.Builder().code(304).build())
        return answer(path, MockResponse.Builder()
            .code(200)
            .addHeader("Content-Type", file.contentType)
            .apply { file.etag?.let { addHeader("ETag", it) } }
            .apply { file.lastModified?.let { addHeader("Last-Modified", it) } }
            .body(okio.Buffer().write(file.bytes))
            .build())
    }

    private fun answer(path: String, response: MockResponse) = response.also { responses += path to it.code }

    private val dropConnection = MockResponse.Builder()
        .onRequestStart(SocketEffect.CloseSocket())
        .onResponseStart(SocketEffect.CloseSocket())
        .build()
}
