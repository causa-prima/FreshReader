package io.github.causaprima.freshreader.core

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** A favicon as the Server delivers it. */
internal sealed interface ServerFavicon {
    class Image(val bytes: ByteArray) : ServerFavicon
    /** An SVG, which Android cannot show. */
    data object Svg : ServerFavicon
    /** FreshRSS found none, or it cannot be loaded. */
    data object None : ServerFavicon
}

/**
 * Loads Feed favicons from FreshRSS's `f.php`, which needs no login. When FreshRSS found no favicon for a
 * Feed, `f.php` serves its own default icon, named `default_favicon.ico`; that counts as none, so that the
 * list can tell Feeds apart by their placeholders instead. Loaded favicons are kept for the app's lifetime.
 */
internal class Favicons(private val http: OkHttpClient) {
    private val loaded = ConcurrentHashMap<String, ServerFavicon>()

    /** The favicon at [url]; failures are not kept, so that the next call tries again. */
    fun load(url: String): ServerFavicon = loaded[url] ?: download(url).also { if (it != ServerFavicon.None) loaded[url] = it }

    private fun download(url: String): ServerFavicon {
        val request = runCatching { Request.Builder().url(url).build() }.getOrNull() ?: return ServerFavicon.None
        return try {
            http.newCall(request).execute().use { response ->
                val isDefault = response.header("Content-Disposition").orEmpty().contains("default_favicon")
                if (!response.isSuccessful || isDefault) return ServerFavicon.None
                val bytes = response.body.bytes()
                when (imageKind(response.header("Content-Type"), bytes)) {
                    ImageKind.Bitmap -> ServerFavicon.Image(bytes)
                    ImageKind.Svg -> ServerFavicon.Svg
                    ImageKind.Other -> ServerFavicon.None
                }
            }
        } catch (_: IOException) {
            ServerFavicon.None
        }
    }
}

internal enum class ImageKind { Bitmap, Svg, Other }

/**
 * What [bytes] served as [contentType] are. FreshRSS serves an SVG favicon as `image/svg+xml` only where PHP
 * can tell file types, otherwise as `image/x-icon`; so SVG is also recognised by its markup.
 */
internal fun imageKind(contentType: String?, bytes: ByteArray): ImageKind {
    val type = contentType.orEmpty().lowercase()
    val start = bytes.take(256).toByteArray().decodeToString().trimStart('﻿', ' ', '\t', '\r', '\n')
    return when {
        "svg" in type || start.startsWith("<") -> if ("svg" in type || "<svg" in start) ImageKind.Svg else ImageKind.Other
        bytes.isEmpty() -> ImageKind.Other
        type.startsWith("image/") -> ImageKind.Bitmap
        else -> ImageKind.Other
    }
}
