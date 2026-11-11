package io.github.causaprima.freshreader.core

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** What the Article View shows of an Article in the Reader View, decided from the loaded Original Page. */
sealed interface ReaderView {
    /** The page's main content, prepared like Feed Content. */
    data class Ready(val document: String) : ReaderView

    /**
     * The page itself is shown, as it is not, or not yet, the Article: a consent manager shows its wall, the site has
     * sent the reader to another page, e.g. its consent page, or the page could not be loaded. The reader deals with
     * the visible page. Asked again until the Article is there. Walls built by a page's scripts do not appear when
     * the page is loaded as text and layout only (ADR 0002).
     */
    data object ShowPage : ReaderView

    /** The page is still loading its document, or its consent manager has not decided yet whether to show its wall: ask again shortly. */
    data object NotYet : ReaderView

    /**
     * Readability found no main content, or the script could not report. Loaded as text and layout only, the reader
     * is offered to load the complete page; loaded completely, the Original Page is shown instead (ADR 0002).
     */
    data object Failed : ReaderView
}

/** How much of an Original Page the in-app browser loads for the Reader View. */
enum class PageLoad {
    /** Only the page itself and its stylesheets; see ADR 0002. */
    TextAndLayout,

    /** Everything, as a browser would: to let the reader consent, or when the reader asks for it. */
    Complete,
}

/** What the Reader View script reports from the loaded Original Page. */
@Serializable
internal class PageReport(
    /** Whether the browser is still reading the page's document; its main content may be incomplete then. */
    val loading: Boolean = false,
    /** The address of the page the browser shows. */
    val url: String? = null,
    /** The consent manager's state from the IAB TCF API's `ping`; null if the page has no TCF API. */
    val tcf: TcfPing? = null,
    /** The main content Readability found; null if it found none. */
    val content: String? = null,
)

/** The TCF API's `ping` answer, as far as the Reader View needs it. */
@Serializable
internal class TcfPing(val loaded: Boolean, val displayStatus: String? = null)

/**
 * Whether the browser, asked to load [requested], still shows that page at [shown]. A site that sends the reader to
 * another of its pages, e.g. its consent page, shows another page; a redirect to https, to a trailing slash, or to
 * another site, e.g. from a Feed's tracking link to the Article, still leads to the requested page.
 */
internal fun isSamePage(shown: String, requested: String): Boolean {
    val page = shown.toHttpUrlOrNull() ?: return false
    val asked = requested.toHttpUrlOrNull() ?: return true
    return page.host != asked.host || page.encodedPath.trimEnd('/') == asked.encodedPath.trimEnd('/')
}

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
 * Runs in the Original Page and returns its report as a JSON string: whether the document is still loading, the
 * page's address, the consent manager's state from the IAB TCF API, and the main content found by Mozilla Readability.js 0.6.0
 * (Apache License 2.0, bundled as a resource). Readability works on a copy of the page and is wrapped in a function.
 *
 * Text that the page shows only to screen readers, e.g. "(opens in a new window)" after each link, is left out: such
 * text is clipped to an invisible box, which only the laid-out page reveals, so the script marks it there before
 * copying the page and removes the marks again.
 */
internal val READER_VIEW_SCRIPT: String by lazy {
    val readability = checkNotNull(PageReport::class.java.getResourceAsStream("/readability/Readability.js")) { "Readability.js missing" }
        .bufferedReader().use { it.readText() }
    """
    |(function () {
    |if (document.readyState === "loading") return JSON.stringify({ loading: true });
    |$readability
    |var tcf = null;
    |if (typeof window.__tcfapi === "function") {
    |  try {
    |    window.__tcfapi("ping", 2, function (ping) { tcf = { loaded: !!ping.cmpLoaded, displayStatus: ping.displayStatus || null }; });
    |  } catch (e) {}
    |  if (tcf === null) tcf = { loaded: false, displayStatus: null };
    |}
    |var mark = "data-freshreader-screen-reader-only", marked = [], all = document.body ? document.body.getElementsByTagName("*") : [];
    |for (var i = 0; i < all.length; i++) {
    |  var box = all[i].getBoundingClientRect();
    |  if (box.width > 1 || box.height > 1) continue;
    |  var style = getComputedStyle(all[i]);
    |  if (style.display === "none" || (style.position !== "absolute" && style.position !== "fixed")) continue;
    |  if (style.clip === "auto" && style.clipPath === "none" && style.overflow !== "hidden") continue;
    |  all[i].setAttribute(mark, "");
    |  marked.push(all[i]);
    |}
    |var copy = document.cloneNode(true);
    |marked.forEach(function (element) { element.removeAttribute(mark); });
    |copy.querySelectorAll("[" + mark + "]").forEach(function (element) { element.remove(); });
    |var article = null;
    |try { article = new Readability(copy).parse(); } catch (e) {}
    |return JSON.stringify({ url: location.href, tcf: tcf, content: article ? article.content : null });
    |})()
    """.trimMargin()
}
