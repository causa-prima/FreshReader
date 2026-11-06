package io.github.causaprima.freshreader.core

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Feeds delivered through follow.it, such as Der Postillon, link each Article to a follow.it tracking link.
 * Opened, it answers with a redirect to `api.follow.it/redirect-to-url?q=<real link>`, which a browser shows as
 * an interstitial "Redirect Notice". This finds the real link, asking follow.it once, without following on.
 */
internal class FollowIt(http: OkHttpClient) {
    private val http = http.newBuilder().followRedirects(false).followSslRedirects(false).build()

    /** The real link behind [link], or [link] itself if it is no follow.it link or follow.it cannot tell. */
    fun unwrap(link: String): String {
        val url = link.toHttpUrlOrNull()?.takeIf { it.host == HOST } ?: return link
        val target = targetOf(url) ?: redirectOf(url)?.let(::targetOf) ?: return link
        return target.withoutFollowItTracking().toString()
    }

    /** The target of a `redirect-to-url` link. */
    private fun targetOf(url: HttpUrl): HttpUrl? =
        if (url.host == HOST && url.encodedPath == "/redirect-to-url") url.queryParameter("q")?.toHttpUrlOrNull() else null

    private fun redirectOf(url: HttpUrl): HttpUrl? = try {
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (response.isRedirect) response.header("Location")?.let { url.resolve(it) } else null
        }
    } catch (_: IOException) {
        null
    }

    private fun HttpUrl.withoutFollowItTracking(): HttpUrl =
        if (queryParameter("utm_source") == "follow.it") newBuilder().removeAllQueryParameters("utm_source").build() else this

    private companion object {
        const val HOST = "api.follow.it"
    }
}
