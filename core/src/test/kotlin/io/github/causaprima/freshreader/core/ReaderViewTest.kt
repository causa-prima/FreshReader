package io.github.causaprima.freshreader.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The Reader View: the Core's script runs in the loaded Original Page and reports what it found there; the
 * Core turns that report into what the Article View shows.
 */
class ReaderViewTest : LoggedInCoreTest() {
    private fun addArticle(title: String, feed: String): Article = runBlocking {
        server.addFeed(feed, category = "News")
        server.addArticle(title, feedTitle = feed, published = 1_793_595_600, url = "https://example.org/$title", content = "<p>The Feed's teaser.</p>")
        assertEquals(Outcome.Ok, core.refresh())
        core.articles.single { it.title == title }
    }

    @Test
    fun `the Reader View shows the page's main content under the Article's header, laid out like Feed Content`() =
        runBlocking<Unit> {
            val article = addArticle("Cybertruck", feed = "Golem.de")
            core.setFeedSettings(article.feedId, FeedSettings(viewMode = ViewMode.ReaderView, bonusText = true))

            val shown = core.readerView(article, article.url!!, PageLoad.Complete, pageReport(consentWall = CONSENT_GIVEN, mainContent = MAIN_CONTENT))

            val document = assertIs<ReaderView.Ready>(shown).document
            assertEquals("Cybertruck", Jsoup.parse(document).selectFirst("h1")?.text())
            assertContains(textAfter(document, "h1"), "Golem.de")
            assertContains(textAfter(document, "h1"), "The production ends in 2027.")
            assertContains(textAfter(document, "img"), "The truck in Texas.") // Bonus Text
            assertEquals(1, Jsoup.parse(document).select("table").size)
            assertEquals(emptyList(), Jsoup.parse(document).select("table[width]").map { it.tagName() })
            assertEquals(false, "The Feed's teaser." in visibleText(document))
        }

    @Test
    fun `while a consent wall is shown, the Reader View asks for consent, although the content lies beneath the wall`() {
        // Der Postillon: its pay-or-consent wall covers the Article, which is complete in the page underneath.
        val article = addArticle("Newsticker", feed = "Der Postillon")

        val shown = core.readerView(article, article.url!!, PageLoad.Complete, pageReport(consentWall = CONSENT_WALL_SHOWN, mainContent = MAIN_CONTENT))

        assertEquals(ReaderView.ShowPage, shown)
    }

    @Test
    fun `a page the site has sent the reader to instead of the Article, e g a consent page, is shown as it is`() {
        // Golem.de: without consent it redirects to its consent page. Once the reader consents there, the consent
        // manager shows no wall any more, before the site returns to the Article; its text is not the Article's.
        val article = addArticle("Copernicus", feed = "Golem.de")
        val consentPage = "https://example.org/sonstiges/zustimmung/auswahl.html?from=https%3A%2F%2Fexample.org%2FCopernicus"

        val shown = core.readerView(article, article.url!!, PageLoad.Complete, pageReport(CONSENT_GIVEN, CONSENT_TEXT, pageUrl = consentPage))

        assertEquals(ReaderView.ShowPage, shown)
    }

    @Test
    fun `if the page could not be loaded, the browser's error page is shown as it is, not taken for the Article`() {
        // On a congested connection, the page timed out; Readability finds the error page's text as main content.
        val article = addArticle("Copernicus", feed = "Golem.de")
        val errorPage = pageReport(consentWall = null, mainContent = "<p>This site can't be reached</p>", pageUrl = "chrome-error://chromewebdata/")

        assertEquals(ReaderView.ShowPage, core.readerView(article, article.url!!, PageLoad.Complete, errorPage))
    }

    @Test
    fun `the Article's page still counts as the Article after a redirect to https, to a trailing slash or to another site`() {
        // Feeds link through tracking sites, and sites redirect to their https address; both lead to the Article.
        val article = addArticle("Copernicus", feed = "Golem.de")

        for (pageUrl in listOf("https://example.org/Copernicus/", "https://news.example.com/2026/copernicus.html")) {
            val shown = core.readerView(article, "http://example.org/Copernicus", PageLoad.Complete, pageReport(CONSENT_GIVEN, MAIN_CONTENT, pageUrl = pageUrl))
            assertIs<ReaderView.Ready>(shown, pageUrl)
        }
    }

    @Test
    fun `while the consent manager is still loading, the Reader View waits instead of assuming there is no wall`() {
        // Golem.de: 1.5 s after the page had loaded, its consent manager had not decided yet.
        val article = addArticle("Copernicus", feed = "Golem.de")

        val shown = core.readerView(article, article.url!!, PageLoad.Complete, pageReport(consentWall = Tcf(loaded = false, displayStatus = null), mainContent = CONSENT_TEXT))

        assertEquals(ReaderView.NotYet, shown)
    }

    @Test
    fun `while the page's document is still loading, the Reader View waits instead of showing a part of it`() {
        // The Reader View checks the page as soon as it can, before the page's ads and images have loaded.
        val article = addArticle("Copernicus", feed = "Golem.de")

        val shown = core.readerView(article, article.url!!, PageLoad.Complete, pageReport(consentWall = null, mainContent = MAIN_CONTENT, documentLoaded = false))

        assertEquals(ReaderView.NotYet, shown)
    }

    @Test
    fun `if the page has no main content, the Reader View gives way to the Original Page`() {
        // Most sites have no consent manager; the Reader View depends only on Readability finding the main content.
        val article = addArticle("Elevators", feed = "xkcd.com")

        assertIs<ReaderView.Ready>(core.readerView(article, article.url!!, PageLoad.Complete, pageReport(consentWall = null, mainContent = MAIN_CONTENT)))
        assertEquals(ReaderView.Failed, core.readerView(article, article.url!!, PageLoad.Complete, pageReport(consentWall = null, mainContent = null)))
        assertEquals(ReaderView.Failed, core.readerView(article, article.url!!, PageLoad.Complete, pageReport(consentWall = CONSENT_GIVEN, mainContent = null)))
    }

    @Test
    fun `if the script could not report, say while the page navigates, the Reader View gives way to the Original Page`() {
        val article = addArticle("Elevators", feed = "xkcd.com")

        assertEquals(ReaderView.Failed, core.readerView(article, article.url!!, PageLoad.Complete, "null")) // what evaluateJavascript returns then
    }

    @Test
    fun `loaded as text and layout only, the page shows no consent wall, so its consent manager is not waited for`() {
        // Der Postillon: its consent manager is a script, which is not loaded; the Article is complete in the page.
        val article = addArticle("Newsticker", feed = "Der Postillon")
        val stillLoading = Tcf(loaded = false, displayStatus = null)

        val shown = core.readerView(article, article.url!!, PageLoad.TextAndLayout, pageReport(stillLoading, MAIN_CONTENT))

        assertIs<ReaderView.Ready>(shown)
    }

    @Test
    fun `loaded as text and layout only, a page the site sent the reader to instead of the Article is still shown`() {
        // Golem.de redirects on its server to its consent page, scripts or not.
        val article = addArticle("Copernicus", feed = "Golem.de")
        val consentPage = "https://example.org/sonstiges/zustimmung/auswahl.html"

        val shown = core.readerView(article, article.url!!, PageLoad.TextAndLayout, pageReport(null, CONSENT_TEXT, pageUrl = consentPage))

        assertEquals(ReaderView.ShowPage, shown)
    }

    @Test
    fun `loading text and layout only, the Reader View lets through the page itself and its stylesheets, nothing else`() {
        // What the in-app browser asks for, as Chromium describes it: whether it is the page itself, and what it accepts.
        val allowed = mapOf(
            "the page itself" to core.readerViewLoads(isPageItself = true, accept = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"),
            "a stylesheet" to core.readerViewLoads(isPageItself = false, accept = "text/css,*/*;q=0.1"),
            "a script, or a font" to core.readerViewLoads(isPageItself = false, accept = "*/*"),
            "an image" to core.readerViewLoads(isPageItself = false, accept = "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"),
            "an ad's frame" to core.readerViewLoads(isPageItself = false, accept = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"),
            "a request by a script" to core.readerViewLoads(isPageItself = false, accept = null),
        )

        assertEquals(mapOf("the page itself" to true, "a stylesheet" to true), allowed.filterValues { it })
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
