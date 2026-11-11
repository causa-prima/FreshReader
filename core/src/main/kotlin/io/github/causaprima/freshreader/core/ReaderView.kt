package io.github.causaprima.freshreader.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.time.Clock
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration

/** What the Article View shows of an Article, following its Feed's View Mode. */
sealed interface ArticlePage {
    /** The Core's prepared document: header and Feed Content. */
    data class FeedContent(val document: String) : ArticlePage

    /** The Reader View of the Article's Original Page at [pageUrl]; see [ReaderViewSession]. */
    data class ReaderView(val pageUrl: String) : ArticlePage

    /** The Article's Original Page at [pageUrl], in the in-app browser. */
    data class OriginalPage(val pageUrl: String) : ArticlePage
}

/** What the Reader View shows the reader. */
sealed interface ReaderViewState {
    /** The page is loading, or being read; nothing to see yet. */
    data object Loading : ReaderViewState

    /** The page's main content, prepared like Feed Content. */
    data class Ready(val document: String) : ReaderViewState

    /**
     * The page itself, as it is not, or not yet, the Article: the site sent the reader to another page, e.g. its
     * consent page, a consent manager shows its wall, the page could not be loaded, or, loaded completely, it holds no
     * main content. The reader deals with the visible page.
     */
    data object Page : ReaderViewState

    /** Loaded as text and layout only, the page holds no main content; the reader may load the complete page. */
    data object NothingFound : ReaderViewState

    /** The page could not be read, as the script did not report in time; the reader may load the complete page. */
    data object Unreadable : ReaderViewState
}

/**
 * The Reader View of one Article: drives the in-app browser that loads the Article's Original Page at [pageUrl], and
 * decides what the reader sees (ADR 0002).
 *
 * The page loads as text and layout only: no scripts, ads, trackers, images or fonts. The browser runs [script] in the
 * page once it has changed, [pageChanged], and hands the result to [reported], which says what to do next, until the
 * main content is [ReaderViewState.Ready]. A page the site sent the reader to instead, e.g. its consent page, is
 * loaded completely, so that the reader can decide there; the Article after it again as text and layout only.
 * If the page holds no main content, or cannot be read within 15 s, the reader is told so and may [loadCompletely].
 */
class ReaderViewSession internal constructor(
    val pageUrl: String,
    /** Prepares the main content for the Article View, following the Feed Settings as they are then. */
    private val prepare: (content: String) -> String,
    private val clock: Clock,
) {
    /** What the browser does next, after a report. */
    sealed interface Next {
        /** Run the script again after [delay]. */
        data class CheckAgainIn(val delay: Duration) : Next

        /** Load the shown page again, now as far as [letsThrough] says. */
        data object Reload : Next

        /** Load the shown page again once the reader sees it: a neighbouring Article met a consent page. */
        data object ReloadWhenVisible : Next

        /** Nothing more to check, until the page changes. */
        data object Stop : Next
    }

    private enum class PageLoad { TextAndLayout, ForConsent, Complete }

    @Volatile
    private var pageLoad = PageLoad.TextAndLayout
    /** Whether the site has sent the reader to another page than the Article, e.g. its consent page. */
    @Volatile
    private var sentElsewhere = false
    /** Whether the browser has asked for a page that it does not show yet; reports until then are of the page before. */
    @Volatile
    private var navigating = false
    @Volatile
    private var pageChangedAt: Instant = clock.instant()
    private var content: String? = null
    private val _state = MutableStateFlow<ReaderViewState>(ReaderViewState.Loading)

    /** What the reader sees. */
    val state: StateFlow<ReaderViewState> = _state

    /** The script to run in the page; its result goes to [reported]. */
    val script: String get() = READER_VIEW_SCRIPT

    /**
     * Whether the browser loads a request for [url]: the page itself, [isPageItself], or something it needs, by what
     * it [accept]s. Loaded as text and layout only: the page and its stylesheets. Called off the main thread.
     */
    fun letsThrough(url: String, isPageItself: Boolean, accept: String?): Boolean {
        if (isPageItself) {
            navigating = true
            // Once sent elsewhere: the Article loads as text and layout only, any other page completely.
            if (sentElsewhere && pageLoad != PageLoad.Complete) {
                pageLoad = if (isArticlePage(url)) PageLoad.TextAndLayout else PageLoad.ForConsent
            }
        }
        return pageLoad != PageLoad.TextAndLayout || isPageItself || accept?.startsWith("text/css") == true
    }

    /** The browser shows another page, or has finished loading it: it is read again from now on. */
    fun pageChanged() {
        navigating = false
        pageChangedAt = clock.instant()
    }

    /** The script's [report] from the page the browser shows, which the reader sees, if [visible], or not. */
    fun reported(report: String, visible: Boolean): Next {
        val page = pageReport(report) ?: return giveUp(ReaderViewState.Unreadable)
        if (page.loading) return waitOrGiveUp()
        if (page.url != null && !isSamePage(page.url, pageUrl)) return showPage(visible)
        if (pageLoad != PageLoad.TextAndLayout) page.tcf?.let { consentManager ->
            if (!consentManager.loaded) return waitOrGiveUp()
            if (consentManager.displayStatus == "visible") return showPage(visible)
        }
        val found = page.content ?: return giveUp(ReaderViewState.NothingFound)
        content = found
        _state.value = ReaderViewState.Ready(prepare(found))
        return Next.Stop
    }

    /**
     * The reader asks for the complete page, e.g. as it holds no main content as text and layout only; the browser
     * loads the page again.
     */
    fun loadCompletely() {
        pageLoad = PageLoad.Complete
        _state.value = ReaderViewState.Loading
    }

    /** Prepares the shown main content again, e.g. as the Feed Settings changed. */
    fun prepareAgain() {
        val found = content ?: return
        if (_state.value is ReaderViewState.Ready) _state.value = ReaderViewState.Ready(prepare(found))
    }

    /** The site shows another page than the Article, or a wall over it: the reader deals with it. */
    private fun showPage(visible: Boolean): Next {
        if (!visible) return Next.ReloadWhenVisible
        if (navigating) return Next.CheckAgainIn(CHECK) // e.g. the consent page, while the browser returns to the Article
        if (pageLoad == PageLoad.TextAndLayout) {
            sentElsewhere = true
            pageLoad = PageLoad.ForConsent
            _state.value = ReaderViewState.Loading
            return Next.Reload
        }
        _state.value = ReaderViewState.Page
        return Next.CheckAgainIn(CONSENT_CHECK)
    }

    private fun waitOrGiveUp(): Next {
        if (java.time.Duration.between(pageChangedAt, clock.instant()).toKotlinDuration() < MAX_WAIT) return Next.CheckAgainIn(CHECK)
        return giveUp(ReaderViewState.Unreadable)
    }

    /** Loaded as text and layout only, the reader is told [why]; loaded completely, the page is shown as it is. */
    private fun giveUp(why: ReaderViewState): Next {
        _state.value = if (pageLoad == PageLoad.TextAndLayout) why else ReaderViewState.Page
        return Next.Stop
    }

    /** Whether [url] is the Article's page itself, on its own site, as after a consent page. */
    private fun isArticlePage(url: String): Boolean {
        val page = url.toHttpUrlOrNull() ?: return false
        val article = pageUrl.toHttpUrlOrNull() ?: return false
        return page.host == article.host && page.encodedPath.trimEnd('/') == article.encodedPath.trimEnd('/')
    }

    private companion object {
        val CHECK = 250.milliseconds
        val CONSENT_CHECK = 1.seconds
        val MAX_WAIT = 15.seconds
    }
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
