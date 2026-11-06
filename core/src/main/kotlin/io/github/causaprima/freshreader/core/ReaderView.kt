package io.github.causaprima.freshreader.core

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What the Article View shows of an Article in the Reader View, decided from the loaded Original Page. */
sealed interface ReaderView {
    /** The page's main content, prepared like Feed Content. */
    data class Ready(val document: String) : ReaderView

    /** A consent manager shows its wall: the reader decides on the visible page, the Reader View does not get around it. */
    data object ConsentNeeded : ReaderView

    /** The page's consent manager has not decided yet whether to show its wall: ask again shortly. */
    data object NotYet : ReaderView

    /** Readability found no main content: the Original Page is shown instead. */
    data object Failed : ReaderView
}

/** What the Reader View script reports from the loaded Original Page. */
@Serializable
internal class PageReport(
    /** The consent manager's state from the IAB TCF API's `ping`; null if the page has no TCF API. */
    val tcf: TcfPing? = null,
    /** The main content Readability found; null if it found none. */
    val content: String? = null,
)

/** The TCF API's `ping` answer, as far as the Reader View needs it. */
@Serializable
internal class TcfPing(val loaded: Boolean, val displayStatus: String? = null)

private val reportFormat = Json { ignoreUnknownKeys = true }

/**
 * Reads [evaluated], the script's result as `evaluateJavascript` hands it over: a JSON string holding the report.
 * Null if there is none, e.g. `null` while the page navigates.
 */
internal fun pageReport(evaluated: String): PageReport? = try {
    reportFormat.decodeFromString<String?>(evaluated)?.let { reportFormat.decodeFromString<PageReport>(it) }
} catch (_: SerializationException) {
    null
}

/**
 * Runs in the loaded Original Page and returns its report as a JSON string: the consent manager's state from the
 * IAB TCF API, and the main content found by Mozilla Readability.js 0.6.0 (Apache License 2.0, bundled as a
 * resource). Readability works on a copy of the page and is wrapped in a function, so the page stays untouched.
 */
internal val READER_VIEW_SCRIPT: String by lazy {
    val readability = checkNotNull(PageReport::class.java.getResourceAsStream("/readability/Readability.js")) { "Readability.js missing" }
        .bufferedReader().use { it.readText() }
    """
    |(function () {
    |$readability
    |var tcf = null;
    |if (typeof window.__tcfapi === "function") {
    |  try {
    |    window.__tcfapi("ping", 2, function (ping) { tcf = { loaded: !!ping.cmpLoaded, displayStatus: ping.displayStatus || null }; });
    |  } catch (e) {}
    |  if (tcf === null) tcf = { loaded: false, displayStatus: null };
    |}
    |var article = null;
    |try { article = new Readability(document.cloneNode(true)).parse(); } catch (e) {}
    |return JSON.stringify({ tcf: tcf, content: article ? article.content : null });
    |})()
    """.trimMargin()
}
