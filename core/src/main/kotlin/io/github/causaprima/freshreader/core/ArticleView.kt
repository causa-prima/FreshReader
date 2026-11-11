package io.github.causaprima.freshreader.core

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * The Article prepared for the Article View: one HTML document with a header (title, Feed, publication
 * date) followed by [content], by default the Feed Content, laid out to the screen width and not zoomable.
 */
internal fun articleViewDocument(
    article: Article,
    settings: FeedSettings,
    dateFormat: DateTimeFormatter,
    content: String = article.content,
): String {
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
        |${prepareContent(content, settings)}
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
    table { width: 100%; table-layout: fixed; }
    td, th { overflow-wrap: anywhere; }
    .$BONUS_TEXT { display: block; margin: 0.5em 0; font-style: italic; }
""".trimIndent()

private const val BONUS_TEXT = "bonus-text"
private val PLAIN_HTTP_IN_SRCSET = Regex("""(^|,)(\s*)http://""", RegexOption.IGNORE_CASE)

/** Feed Content or a Reader View's main content as the Article View shows it, following the Feed's [settings]. */
private fun prepareContent(html: String, settings: FeedSettings): String {
    val document = Jsoup.parseBodyFragment(html)
    document.outputSettings().prettyPrint(false)
    val content = document.body()
    loadEmbedsOverHttps(content)
    tameFixedWidths(content)
    if (settings.bonusText) showBonusText(content)
    return content.html()
}

/**
 * Embedded images and media over plain http would be blocked by Android and the WebView, so they are
 * requested over https instead; Dinosaur Comics, for one, embeds its comic over http. Links stay as they are.
 */
private fun loadEmbedsOverHttps(content: Element) {
    for (element in content.select("[src]")) element.attr("src", overHttps(element.attr("src")))
    for (element in content.select("[srcset]")) {
        element.attr("srcset", PLAIN_HTTP_IN_SRCSET.replace(element.attr("srcset")) { "${it.groupValues[1]}${it.groupValues[2]}https://" })
    }
}

/**
 * Layouts made for a desktop screen, such as Dinosaur Comics' 740 px wide tables, would overflow a phone.
 * Their widths are dropped; the stylesheet then fits every table to the screen width.
 */
private fun tameFixedWidths(content: Element) {
    for (element in content.select("table[width], col[width], colgroup[width], td[width], th[width]")) element.removeAttr("width")
}

/**
 * Below each image with a `title`: the title, and the `alt` text too if it says something else. After the
 * Feed Content: the subject of each `mailto:` link, read after the comic as on the web, not in a link bar.
 */
private fun showBonusText(content: Element) {
    for (image in content.select("img[title]")) {
        val title = image.attr("title").trim()
        if (title.isEmpty()) continue
        val alt = image.attr("alt").trim()
        val texts = listOf(title, alt).filter { it.isNotEmpty() }.distinct()
        // An image inside a link: the text goes after the link, so that tapping it does not open the link.
        var previous = image.closest("a") ?: image
        for (text in texts) {
            val bonusText = Element("span").addClass(BONUS_TEXT).text(text)
            previous.after(bonusText)
            previous = bonusText
        }
    }
    for (link in content.select("a[href^=mailto:]")) {
        val subject = mailSubject(link.attr("href")) ?: continue
        content.appendChild(Element("span").addClass(BONUS_TEXT).text("✉ $subject"))
    }
}

/** The decoded `subject` of a `mailto:` link (RFC 6068), or null if it has none. */
private fun mailSubject(href: String): String? {
    val query = href.substringAfter('?', missingDelimiterValue = "")
    val encoded = query.split('&').firstOrNull { it.substringBefore('=').equals("subject", ignoreCase = true) }
        ?.substringAfter('=', missingDelimiterValue = "") ?: return null
    // In mailto, "+" is a plus sign, not an encoded space.
    val subject = runCatching { URLDecoder.decode(encoded.replace("+", "%2B"), Charsets.UTF_8) }.getOrDefault(encoded)
    return subject.trim().ifEmpty { null }
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
