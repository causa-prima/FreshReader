package io.github.causaprima.freshreader.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jsoup.Jsoup
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The Reader View: the Core's script runs in the loaded Original Page and reports what it found there; the
 * Core turns that report into what the Article View shows.
 */
class ReaderViewTest {
    private val server = FakeFreshRss().start()

    @TempDir
    lateinit var storeDir: File

    private lateinit var core: Core

    @BeforeEach
    fun logIn() = runBlocking<Unit> {
        core = Core(storeDir = storeDir, secretBox = TestSecretBox())
        assertEquals(Outcome.Ok, core.logIn(server.url, "reader", "api-secret"))
    }

    @AfterEach
    fun tearDown() {
        core.close()
        server.close()
    }

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

            val shown = core.readerView(article, pageReport(consentWall = CONSENT_GIVEN, mainContent = MAIN_CONTENT))

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

        val shown = core.readerView(article, pageReport(consentWall = CONSENT_WALL_SHOWN, mainContent = MAIN_CONTENT))

        assertEquals(ReaderView.ConsentNeeded, shown)
    }

    @Test
    fun `while the consent manager is still loading, the Reader View waits instead of assuming there is no wall`() {
        // Golem.de: 1.5 s after the page had loaded, its consent manager had not decided yet.
        val article = addArticle("Copernicus", feed = "Golem.de")

        val shown = core.readerView(article, pageReport(consentWall = Tcf(loaded = false, displayStatus = null), mainContent = CONSENT_TEXT))

        assertEquals(ReaderView.NotYet, shown)
    }

    @Test
    fun `if the page has no main content, the Reader View gives way to the Original Page`() {
        // Most sites have no consent manager; the Reader View depends only on Readability finding the main content.
        val article = addArticle("Elevators", feed = "xkcd.com")

        assertIs<ReaderView.Ready>(core.readerView(article, pageReport(consentWall = null, mainContent = MAIN_CONTENT)))
        assertEquals(ReaderView.Failed, core.readerView(article, pageReport(consentWall = null, mainContent = null)))
        assertEquals(ReaderView.Failed, core.readerView(article, pageReport(consentWall = CONSENT_GIVEN, mainContent = null)))
    }

    @Test
    fun `if the script could not report, say while the page navigates, the Reader View gives way to the Original Page`() {
        val article = addArticle("Elevators", feed = "xkcd.com")

        assertEquals(ReaderView.Failed, core.readerView(article, "null")) // what evaluateJavascript returns then
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
 * the consent manager's state, or null if the page has no TCF API, and the main content Readability found.
 */
fun pageReport(consentWall: Tcf?, mainContent: String?): String {
    val report = buildJsonObject {
        put("tcf", consentWall?.let { buildJsonObject { put("loaded", it.loaded); put("displayStatus", it.displayStatus) } } ?: JsonNull)
        put("content", mainContent)
    }
    return Json.encodeToString(JsonPrimitive(report.toString()))
}
