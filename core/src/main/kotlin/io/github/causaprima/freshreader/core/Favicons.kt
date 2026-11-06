package io.github.causaprima.freshreader.core

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Loads Feed favicons from FreshRSS's `f.php`, which needs no login. When FreshRSS found no favicon for a
 * Feed, `f.php` serves its own default icon, named `default_favicon.ico`; that counts as none, so that the
 * list can tell Feeds apart by their placeholders instead. Loaded favicons are kept for the app's lifetime.
 */
internal class Favicons(private val http: OkHttpClient) {
    private val loaded = ConcurrentHashMap<String, ByteArray>()

    /** The favicon at [url] as image bytes; null if it is FreshRSS's default icon or cannot be loaded. */
    fun load(url: String): ByteArray? = loaded[url] ?: download(url)?.also { loaded[url] = it }

    private fun download(url: String): ByteArray? {
        val request = runCatching { Request.Builder().url(url).build() }.getOrNull() ?: return null
        return try {
            http.newCall(request).execute().use { response ->
                val isImage = response.header("Content-Type").orEmpty().startsWith("image/")
                val isDefault = response.header("Content-Disposition").orEmpty().contains("default_favicon")
                if (response.isSuccessful && isImage && !isDefault) response.body.bytes() else null
            }
        } catch (_: IOException) {
            null
        }
    }
}
