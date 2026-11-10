package io.github.causaprima.freshreader.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * Finds a favicon Android can show for a Feed whose Server favicon is SVG: the first one the Feed's site lists
 * that is not SVG, else the site's `/favicon.ico`. What is found, or that nothing was, is kept on the device by
 * site and looked at again after a month at the earliest.
 */
internal class FaviconFallbacks(private val http: OkHttpClient, dir: File, private val clock: Clock) {
    private val store = FaviconFallbackStore(dir)
    private var fallbacks: Map<String, Fallback> = store.load()

    /** The fallback favicon for [site]; null if it has none or it cannot be loaded. */
    @Synchronized
    fun find(site: String): ByteArray? {
        val kept = fallbacks[site]
        if (kept != null && Duration.between(kept.checkedAt, clock.instant()) < RECHECK_AFTER) return kept.bytes
        val found = when (val checked = kept?.let(::revalidate)) {
            is Check.Kept -> checked.fallback
            Check.Unreachable -> return kept.bytes
            Check.Gone, null -> determine(site) ?: return kept?.bytes
        }
        fallbacks = fallbacks + (site to found)
        store.save(fallbacks)
        return found.bytes
    }

    private sealed interface Check {
        class Kept(val fallback: Fallback) : Check
        /** It no longer exists, or the site has none: to be determined anew. */
        data object Gone : Check
        /** The site cannot be reached now; the kept fallback stays. */
        data object Unreachable : Check
    }

    /** Asks for [kept]'s favicon again, conditionally: unchanged (304) keeps it, a new one (200) replaces it. */
    private fun revalidate(kept: Fallback): Check {
        val url = kept.url?.toHttpUrlOrNull() ?: return Check.Gone
        val request = Request.Builder().url(url)
            .apply { kept.etag?.let { header("If-None-Match", it) } }
            .apply { kept.lastModified?.let { header("If-Modified-Since", it) } }
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                when {
                    response.code == 304 -> Check.Kept(kept.checkedNow())
                    response.code == 404 || response.code == 410 -> Check.Gone
                    !response.isSuccessful -> Check.Unreachable
                    else -> fallbackFrom(url, response)?.let { Check.Kept(it) } ?: Check.Gone
                }
            }
        } catch (_: IOException) {
            Check.Unreachable
        }
    }

    /** The site's fallback, or one saying it has none; null if the site cannot be reached now. */
    private fun determine(site: String): Fallback? {
        val siteUrl = site.toHttpUrlOrNull() ?: return null
        val candidates = candidates(siteUrl) ?: return null
        return candidates.firstNotNullOfOrNull { download(it) } ?: Fallback(null, null, null, null, clock.instant())
    }

    /** Forgets [site]'s fallback: the Server has a favicon Android can show again. */
    @Synchronized
    fun drop(site: String) {
        if (site !in fallbacks) return
        fallbacks = fallbacks - site
        store.save(fallbacks)
    }

    private fun Fallback.checkedNow() = Fallback(url, bytes, etag, lastModified, clock.instant())

    /** The favicons [site] lists, SVG left out, then its `/favicon.ico`; null if the site cannot be reached now. */
    private fun candidates(site: HttpUrl): List<HttpUrl>? {
        val listed = try {
            http.newCall(Request.Builder().url(site).build()).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val page = Jsoup.parse(response.peekBody(PAGE_LIMIT).string(), response.request.url.toString())
                page.select("link[rel][href]")
                    .filter { link -> link.attr("rel").lowercase().split(Regex("\\s+")).contains("icon") }
                    .filterNot { link -> "svg" in link.attr("type").lowercase() }
                    .mapNotNull { link -> link.absUrl("href").toHttpUrlOrNull() }
                    .filterNot { it.encodedPath.lowercase().endsWith(".svg") }
            }
        } catch (_: IOException) {
            return null
        }
        return (listed + site.resolve("/favicon.ico")).filterNotNull().distinct()
    }

    private fun download(url: HttpUrl): Fallback? = try {
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (response.isSuccessful) fallbackFrom(url, response) else null
        }
    } catch (_: IOException) {
        null
    }

    /** The favicon in a successful [response] from [url]; null if it is not one Android can show. */
    private fun fallbackFrom(url: HttpUrl, response: Response): Fallback? {
        val bytes = response.body.bytes()
        if (imageKind(response.header("Content-Type"), bytes) != ImageKind.Bitmap) return null
        return Fallback(url.toString(), bytes, response.header("ETag"), response.header("Last-Modified"), clock.instant())
    }

    private companion object {
        val RECHECK_AFTER: Duration = Duration.ofDays(30)

        /** Enough of a page for its `<head>`; the rest is not read. */
        const val PAGE_LIMIT = 512L * 1024
    }
}

/**
 * A site's fallback favicon: [url] and its [bytes] with the validators for a conditional request, as of
 * [checkedAt]; [url] and [bytes] are null if the site has none.
 */
internal class Fallback(
    val url: String?,
    val bytes: ByteArray?,
    val etag: String?,
    val lastModified: String?,
    val checkedAt: Instant,
)

/** Keeps the fallback favicons on the device, by site, as plain JSON: favicons are public. */
internal class FaviconFallbackStore(dir: File) {
    private val file = File(dir, "favicon-fallbacks.json")
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private class Stored(
        val url: String? = null,
        val bytes: String? = null,
        val etag: String? = null,
        val lastModified: String? = null,
        val checkedAt: Long,
    )

    fun load(): Map<String, Fallback> {
        if (!file.isFile) return emptyMap()
        return runCatching {
            json.decodeFromString<Map<String, Stored>>(file.readText()).mapValues { (_, s) ->
                Fallback(s.url, s.bytes?.let(Base64.getDecoder()::decode), s.etag, s.lastModified, Instant.ofEpochSecond(s.checkedAt))
            }
        }.getOrDefault(emptyMap())
    }

    fun save(fallbacks: Map<String, Fallback>) {
        val stored = fallbacks.mapValues { (_, f) ->
            Stored(f.url, f.bytes?.let(Base64.getEncoder()::encodeToString), f.etag, f.lastModified, f.checkedAt.epochSecond)
        }
        writeAtomically(file, json.encodeToString(stored).encodeToByteArray())
    }
}
