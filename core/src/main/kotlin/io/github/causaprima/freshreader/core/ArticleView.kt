package io.github.causaprima.freshreader.core

import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * The Article prepared for the Article View: one HTML document with a header (title, Feed, publication
 * date) followed by the Feed Content, laid out to the screen width and not zoomable.
 */
internal fun articleViewDocument(article: Article, dateFormat: DateTimeFormatter): String {
    val date = dateFormat.format(Instant.ofEpochSecond(article.published))
    return """
        |<!DOCTYPE html>
        |<html>
        |<head>
        |<meta charset="utf-8">
        |<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
        |<style>$ARTICLE_VIEW_STYLE</style>
        |</head>
        |<body>
        |<header>
        |<h1>${escapeHtml(article.title)}</h1>
        |<p class="meta">${escapeHtml(article.feedTitle)} · ${escapeHtml(date)}</p>
        |</header>
        |<article>
        |${loadEmbedsOverHttps(article.content)}
        |</article>
        |</body>
        |</html>
    """.trimMargin()
}

private val ARTICLE_VIEW_STYLE = """
    :root { color-scheme: light dark; }
    body { margin: 16px; font-family: sans-serif; line-height: 1.5; overflow-wrap: break-word; }
    header { margin-bottom: 1.5em; }
    h1 { font-size: 1.4em; line-height: 1.25; margin: 0 0 0.3em; }
    .meta { margin: 0; opacity: 0.7; font-size: 0.9em; }
    img, video, iframe, svg, embed, object { max-width: 100%; height: auto; }
    pre { white-space: pre-wrap; }
""".trimIndent()

/** `src` and `srcset` attributes, with their value double-quoted, single-quoted or unquoted. */
private val EMBED_ATTRIBUTE = Regex("""(\s(?:src|srcset)\s*=\s*)(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))""", RegexOption.IGNORE_CASE)
private val PLAIN_HTTP = Regex("""\bhttp://""", RegexOption.IGNORE_CASE)

/**
 * Embedded images and media over plain http would be blocked by Android and the WebView, so they are
 * requested over https instead; Dinosaur Comics, for one, embeds its comic over http. Links stay as they are.
 */
private fun loadEmbedsOverHttps(html: String): String = EMBED_ATTRIBUTE.replace(html) { match ->
    val (prefix, doubleQuoted, singleQuoted, unquoted) = match.destructured
    val upgrade = { value: String -> PLAIN_HTTP.replace(value, "https://") }
    prefix + when {
        match.groups[2] != null -> "\"${upgrade(doubleQuoted)}\""
        match.groups[3] != null -> "'${upgrade(singleQuoted)}'"
        else -> upgrade(unquoted)
    }
}

private fun escapeHtml(text: String): String = buildString(text.length) {
    for (c in text) {
        when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(c)
        }
    }
}
