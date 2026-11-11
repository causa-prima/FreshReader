package io.github.causaprima.freshreader.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * The Reader View of an Article: the in-app browser loads its Original Page and tells the Core's Reader View what
 * happens there; the Core's script reports from the page what it found. The Reader View answers what the browser
 * loads, what to do next, and what the reader sees.
 */
class ReaderViewTest : LoggedInCoreTest() {
    private val clock = MutableClock(Instant.parse("2026-11-11T12:00:00Z"))

    override fun newCore() = Core(storeDir = storeDir, secretBox = secretBox, clock = clock)

    private fun addArticle(title: String, feed: String): Article = runBlocking {
        server.addFeed(feed, category = "News")
        server.addArticle(title, feedTitle = feed, published = 1_793_595_600, url = "https://example.org/$title", content = "<p>The Feed's teaser.</p>")
        assertEquals(Outcome.Ok, core.refresh())
        core.articles.single { it.title == title }
    }

    /** The Reader View of [article], its page shown to the reader and loaded as far as its document. */
    private fun readerView(article: Article): ReaderViewSession =
        core.readerView(article, article.url!!).also { it.pageChanged() }

    /** The Reader View of [article] after the reader asked to load the complete page. */
    private fun loadedCompletely(article: Article): ReaderViewSession =
        readerView(article).also { it.loadCompletely(); it.pageChanged() }

    private fun ReaderViewSession.letsThroughImage(url: String = "https://example.org/truck.jpg") =
        letsThrough(url, isPageItself = false, accept = IMAGE)

    @Test
    fun `the Reader View shows the page's main content under the Article's header, laid out like Feed Content`() =
        runBlocking<Unit> {
            val article = addArticle("Cybertruck", feed = "Golem.de")
            core.setFeedSettings(article.feedId, FeedSettings(viewMode = ViewMode.ReaderView, bonusText = true))
            val readerView = readerView(article)

            assertEquals(ReaderViewSession.Next.Stop, readerView.reported(pageReport(consentWall = null, mainContent = MAIN_CONTENT), visible = true))

            val document = assertIs<ReaderViewState.Ready>(readerView.state.value).document
            assertEquals("Cybertruck", Jsoup.parse(document).selectFirst("h1")?.text())
            assertContains(textAfter(document, "h1"), "Golem.de")
            assertContains(textAfter(document, "h1"), "The production ends in 2027.")
            assertContains(textAfter(document, "img"), "The truck in Texas.") // Bonus Text
            assertEquals(1, Jsoup.parse(document).select("table").size)
            assertEquals(emptyList(), Jsoup.parse(document).select("table[width]").map { it.tagName() })
            assertEquals(false, "The Feed's teaser." in visibleText(document))
        }

    @Test
    fun `a change of the Feed Settings prepares the shown main content again, without loading the page again`() =
        runBlocking<Unit> {
            val article = addArticle("Cybertruck", feed = "Golem.de")
            val readerView = readerView(article)
            readerView.reported(pageReport(consentWall = null, mainContent = MAIN_CONTENT), visible = true)
            assertFalse("The truck in Texas." in visibleText(assertIs<ReaderViewState.Ready>(readerView.state.value).document))

            core.setFeedSettings(article.feedId, FeedSettings(viewMode = ViewMode.ReaderView, bonusText = true))
            readerView.prepareAgain()

            assertContains(visibleText(assertIs<ReaderViewState.Ready>(readerView.state.value).document), "The truck in Texas.")
        }

    @Test
    fun `loading text and layout only, the Reader View lets through the page itself and its stylesheets, nothing else`() {
        // What the in-app browser asks for, as Chromium describes it: whether it is the page itself, and what it accepts.
        val readerView = readerView(addArticle("Copernicus", feed = "Golem.de"))
        val page = "https://example.org/Copernicus"
        val allowed = mapOf(
            "the page itself" to readerView.letsThrough(page, isPageItself = true, accept = HTML),
            "a stylesheet" to readerView.letsThrough("https://example.org/site.css", isPageItself = false, accept = "text/css,*/*;q=0.1"),
            "a script, or a font" to readerView.letsThrough("https://example.org/ads.js", isPageItself = false, accept = "*/*"),
            "an image" to readerView.letsThroughImage(),
            "an ad's frame" to readerView.letsThrough("https://ads.example.com/", isPageItself = false, accept = HTML),
            "a request by a script" to readerView.letsThrough("https://example.org/api", isPageItself = false, accept = null),
        )

        assertEquals(mapOf("the page itself" to true, "a stylesheet" to true), allowed.filterValues { it })
    }

    @Test
    fun `a consent page the site sends the reader to is loaded completely, and the Article after it as text and layout only`() {
        // Golem.de redirects on its server to its consent page, scripts or not. The reader decides there, which needs
        // the page's scripts; then the site returns to the Article, which needs none of them (ADR 0002).
        val readerView = readerView(addArticle("Copernicus", feed = "Golem.de"))
        val consentPage = "https://example.org/sonstiges/zustimmung/auswahl.html?from=https%3A%2F%2Fexample.org%2FCopernicus"

        assertEquals(ReaderViewSession.Next.Reload, readerView.reported(pageReport(null, CONSENT_TEXT, pageUrl = consentPage), visible = true))
        assertTrue(readerView.letsThrough(consentPage, isPageItself = true, accept = HTML))
        assertTrue(readerView.letsThroughImage(), "the consent page loads completely")
        readerView.pageChanged()
        assertEquals(
            ReaderViewSession.Next.CheckAgainIn(1.seconds),
            readerView.reported(pageReport(CONSENT_WALL_SHOWN, CONSENT_TEXT, pageUrl = consentPage), visible = true),
        )
        assertEquals(ReaderViewState.Page, readerView.state.value)

        // The reader consents; the site sends the browser back to the Article.
        assertTrue(readerView.letsThrough("https://example.org/Copernicus", isPageItself = true, accept = HTML))
        assertFalse(readerView.letsThroughImage(), "the Article loads as text and layout only")
        assertEquals(
            ReaderViewSession.Next.CheckAgainIn(0.25.seconds),
            readerView.reported(pageReport(CONSENT_GIVEN, CONSENT_TEXT, pageUrl = consentPage), visible = true),
            "the consent page, still shown until the Article is there, is not loaded again",
        )
        assertFalse(readerView.letsThroughImage())
        readerView.pageChanged()
        assertEquals(ReaderViewSession.Next.Stop, readerView.reported(pageReport(null, MAIN_CONTENT), visible = true))
        assertIs<ReaderViewState.Ready>(readerView.state.value)
    }

    @Test
    fun `if the site sends the reader to its consent page again, that page loads completely again`() {
        // E.g. as the consent was not stored; the reader decides again.
        val readerView = readerView(addArticle("Copernicus", feed = "Golem.de"))
        val consentPage = "https://example.org/sonstiges/zustimmung/auswahl.html"
        readerView.reported(pageReport(null, CONSENT_TEXT, pageUrl = consentPage), visible = true)
        readerView.letsThrough("https://example.org/Copernicus", isPageItself = true, accept = HTML)

        assertTrue(readerView.letsThrough(consentPage, isPageItself = true, accept = HTML))

        assertTrue(readerView.letsThroughImage())
    }

    @Test
    fun `a neighbouring Article that meets a consent page loads again once it is shown, as the reader may have consented meanwhile`() {
        val readerView = readerView(addArticle("Copernicus", feed = "Golem.de"))
        val consentPage = "https://example.org/sonstiges/zustimmung/auswahl.html"

        assertEquals(
            ReaderViewSession.Next.ReloadWhenVisible,
            readerView.reported(pageReport(null, CONSENT_TEXT, pageUrl = consentPage), visible = false),
        )
        assertFalse(readerView.letsThroughImage(), "still text and layout only")
    }

    @Test
    fun `loaded completely, a consent wall over the Article is shown, although the content lies beneath the wall`() {
        // Der Postillon: its pay-or-consent wall covers the Article, which is complete in the page underneath.
        val readerView = loadedCompletely(addArticle("Newsticker", feed = "Der Postillon"))

        assertEquals(
            ReaderViewSession.Next.CheckAgainIn(1.seconds),
            readerView.reported(pageReport(consentWall = CONSENT_WALL_SHOWN, mainContent = MAIN_CONTENT), visible = true),
        )
        assertEquals(ReaderViewState.Page, readerView.state.value)
    }

    @Test
    fun `loaded completely, the Reader View waits while the consent manager is still loading instead of assuming there is no wall`() {
        // Golem.de: 1.5 s after the page had loaded, its consent manager had not decided yet.
        val readerView = loadedCompletely(addArticle("Copernicus", feed = "Golem.de"))

        val next = readerView.reported(pageReport(consentWall = Tcf(loaded = false, displayStatus = null), mainContent = CONSENT_TEXT), visible = true)

        assertEquals(ReaderViewSession.Next.CheckAgainIn(0.25.seconds), next)
        assertEquals(ReaderViewState.Loading, readerView.state.value)
    }

    @Test
    fun `loaded as text and layout only, the page shows no consent wall, so its consent manager is not waited for`() {
        // Der Postillon: its consent manager is a script, which is not loaded; the Article is complete in the page.
        val readerView = readerView(addArticle("Newsticker", feed = "Der Postillon"))

        readerView.reported(pageReport(Tcf(loaded = false, displayStatus = null), MAIN_CONTENT), visible = true)

        assertIs<ReaderViewState.Ready>(readerView.state.value)
    }

    @Test
    fun `if the page could not be loaded, the browser's error page is shown as it is, not taken for the Article`() {
        // On a congested connection, the page timed out; Readability finds the error page's text as main content.
        val readerView = loadedCompletely(addArticle("Copernicus", feed = "Golem.de"))

        readerView.reported(pageReport(consentWall = null, mainContent = "<p>This site can't be reached</p>", pageUrl = "chrome-error://chromewebdata/"), visible = true)

        assertEquals(ReaderViewState.Page, readerView.state.value)
    }

    @Test
    fun `the Article's page still counts as the Article after a redirect to https, to a trailing slash or to another site`() {
        // Feeds link through tracking sites, and sites redirect to their https address; both lead to the Article.
        val article = addArticle("Copernicus", feed = "Golem.de")

        for (pageUrl in listOf("https://example.org/Copernicus/", "https://news.example.com/2026/copernicus.html")) {
            val readerView = core.readerView(article, "http://example.org/Copernicus").also { it.pageChanged() }
            readerView.reported(pageReport(null, MAIN_CONTENT, pageUrl = pageUrl), visible = true)
            assertIs<ReaderViewState.Ready>(readerView.state.value, pageUrl)
        }
    }

    @Test
    fun `while the page's document is still loading, the Reader View waits instead of showing a part of it`() {
        // The Reader View checks the page as soon as it can, before the page's ads and images have loaded.
        val readerView = readerView(addArticle("Copernicus", feed = "Golem.de"))

        val next = readerView.reported(pageReport(consentWall = null, mainContent = MAIN_CONTENT, documentLoaded = false), visible = true)

        assertEquals(ReaderViewSession.Next.CheckAgainIn(0.25.seconds), next)
        assertEquals(ReaderViewState.Loading, readerView.state.value)
    }

    @Test
    fun `if the page holds no main content, the reader is offered the complete page, which once loaded is shown as it is`() {
        // A site that builds its articles with scripts yields none as text and layout only (ADR 0002).
        val readerView = readerView(addArticle("Elevators", feed = "xkcd.com"))

        assertEquals(ReaderViewSession.Next.Stop, readerView.reported(pageReport(consentWall = null, mainContent = null), visible = true))
        assertEquals(ReaderViewState.NothingFound, readerView.state.value)

        readerView.loadCompletely()
        assertEquals(ReaderViewState.Loading, readerView.state.value)
        assertTrue(readerView.letsThroughImage())
        readerView.pageChanged()
        readerView.reported(pageReport(consentWall = CONSENT_GIVEN, mainContent = null), visible = true)
        assertEquals(ReaderViewState.Page, readerView.state.value)
    }

    @Test
    fun `if the script cannot report, the reader is told the page could not be read, not that it holds no Article`() {
        val readerView = readerView(addArticle("Elevators", feed = "xkcd.com"))

        readerView.reported("null", visible = true) // what evaluateJavascript returns, e.g. while the page navigates

        assertEquals(ReaderViewState.Unreadable, readerView.state.value)
    }

    @Test
    fun `if the page is not read within 15 seconds, the reader is told it could not be read`() {
        val readerView = readerView(addArticle("Elevators", feed = "xkcd.com"))
        val stillLoading = pageReport(consentWall = null, mainContent = null, documentLoaded = false)

        clock.now += 14.seconds.toJavaDuration()
        assertEquals(ReaderViewSession.Next.CheckAgainIn(0.25.seconds), readerView.reported(stillLoading, visible = true))
        clock.now += 1.seconds.toJavaDuration()
        assertEquals(ReaderViewSession.Next.Stop, readerView.reported(stillLoading, visible = true))

        assertEquals(ReaderViewState.Unreadable, readerView.state.value)
    }

    private companion object {
        /** What the TCF API's `ping` answers once the reader has consented: the consent manager shows nothing. */
        val CONSENT_GIVEN = Tcf(loaded = true, displayStatus = "disabled")

        /** What the TCF API's `ping` answers while the consent manager shows its wall. */
        val CONSENT_WALL_SHOWN = Tcf(loaded = true, displayStatus = "visible")

        /** What Readability extracts from Golem.de's consent page: it looks like an Article. */
        const val CONSENT_TEXT = "<div><h2>Cookies zustimmen</h2><p>Besuchen Sie Golem.de wie gewohnt mit Werbung und Tracking, " +
            "indem Sie der Nutzung aller Cookies zustimmen.</p></div>"

        const val MAIN_CONTENT = "<div><p>The production ends in 2027.</p>" +
            "<img src=\"https://example.org/truck.jpg\" title=\"The truck in Texas.\">" +
            "<table width=\"740\"><tr><td>Year</td><td>Units</td></tr></table></div>"

        /** What the browser accepts for a page and for an image, as Chromium asks. */
        const val HTML = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        const val IMAGE = "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"
    }
}

/** The consent manager's state as the TCF API's `ping` reports it. */
data class Tcf(val loaded: Boolean, val displayStatus: String?)

/**
 * What the Reader View script reports from a loaded page, encoded as `evaluateJavascript` hands it over:
 * whether the page's document has been read completely, the page's address, if it is not the one asked for, the consent manager's state, or null if the page has no
 * TCF API, and the main content Readability found.
 */
fun pageReport(consentWall: Tcf?, mainContent: String?, documentLoaded: Boolean = true, pageUrl: String? = null): String {
    val report = buildJsonObject {
        put("loading", !documentLoaded)
        pageUrl?.let { put("url", it) }
        put("tcf", consentWall?.let { buildJsonObject { put("loaded", it.loaded); put("displayStatus", it.displayStatus) } } ?: JsonNull)
        put("content", mainContent)
    }
    return Json.encodeToString(JsonPrimitive(report.toString()))
}
