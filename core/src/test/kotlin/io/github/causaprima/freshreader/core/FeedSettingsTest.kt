package io.github.causaprima.freshreader.core

import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Feed Settings and the Bonus Text, checked against Feed Content captured from the real Feeds. */
class FeedSettingsTest {
    private val server = FakeFreshRss().start()

    @TempDir
    lateinit var storeDir: File

    private val secretBox = TestSecretBox()
    private lateinit var core: Core

    @BeforeEach
    fun logIn() = runBlocking<Unit> {
        core = Core(storeDir = storeDir, secretBox = secretBox)
        assertEquals(Outcome.Ok, core.logIn(server.url, "reader", "api-secret"))
    }

    @AfterEach
    fun tearDown() {
        core.close()
        server.close()
    }

    private fun article(title: String): Article = core.articles.single { it.title == title }

    /** xkcd 3319 "Elevators", as the xkcd Feed delivers it: title and alt text are identical. */
    private fun addXkcd(): Article = runBlocking {
        server.addFeed("xkcd.com", category = "Pictures")
        server.addArticle("Elevators", feedTitle = "xkcd.com", published = 1_793_595_600, content = fixture("xkcd-3319.html"))
        assertEquals(Outcome.Ok, core.refresh())
        article("Elevators")
    }

    /** Dinosaur Comics 4538, as its Feed delivers it: 740 px wide tables, an image title and a `mailto:` subject. */
    private fun addDinosaurComics(): Article = runBlocking {
        server.addFeed("Dinosaur Comics!", category = "Pictures")
        server.addArticle(
            "NEXT: sherlock batman meets sherlock, batman", feedTitle = "Dinosaur Comics!",
            published = 1_793_606_400, content = fixture("qwantz-4538.html"),
        )
        assertEquals(Outcome.Ok, core.refresh())
        article("NEXT: sherlock batman meets sherlock, batman")
    }

    @Test
    fun `with Bonus Text on, the xkcd title text appears once, below the comic`() = runBlocking<Unit> {
        val elevators = addXkcd()

        core.setFeedSettings(elevators.feedId, FeedSettings(bonusText = true))
        val view = core.prepareArticleView(elevators)

        assertEquals(1, visibleText(view).occurrences(XKCD_TITLE_TEXT))
        assertEquals(1, textAfter(view, "img").occurrences(XKCD_TITLE_TEXT))
    }

    @Test
    fun `with Bonus Text on, Dinosaur Comics shows the comic's title and the contact link's mail subject`() =
        runBlocking<Unit> {
            val comic = addDinosaurComics()

            core.setFeedSettings(comic.feedId, FeedSettings(bonusText = true))
            val view = core.prepareArticleView(comic)

            assertContains(textAfter(view, "img"), DINOSAUR_TITLE_TEXT)
            assertContains(visibleText(view), DINOSAUR_MAIL_SUBJECT)
        }

    @Test
    fun `Bonus Text is off by default`() = runBlocking<Unit> {
        val elevators = addXkcd()
        val comic = addDinosaurComics()

        val texts = listOf(elevators, comic).map { visibleText(core.prepareArticleView(it)) }

        assertFalse(XKCD_TITLE_TEXT in texts[0])
        assertFalse(DINOSAUR_TITLE_TEXT in texts[1])
        assertFalse(DINOSAUR_MAIL_SUBJECT in texts[1])
    }

    @Test
    fun `fixed-width layouts are tamed to the screen width, whatever the Bonus Text`() = runBlocking<Unit> {
        val comic = addDinosaurComics()

        val view = Jsoup.parse(core.prepareArticleView(comic))

        assertEquals(2, view.select("table").size, "the tables stay")
        assertEquals(emptyList(), view.select("table[width], col[width], colgroup[width], td[width], th[width]").map { it.tagName() })
    }

    @Test
    fun `Feed Settings belong to their Feed and survive a restart`() = runBlocking<Unit> {
        val elevators = addXkcd()
        val comic = addDinosaurComics()
        core.setFeedSettings(elevators.feedId, FeedSettings(bonusText = true))

        core.close() // the app is killed
        core = Core(storeDir = storeDir, secretBox = secretBox)
        assertEquals(Outcome.Ok, core.refresh())

        assertEquals(FeedSettings(bonusText = true), core.feedSettings(elevators.feedId))
        assertEquals(FeedSettings(bonusText = false), core.feedSettings(comic.feedId))
        assertContains(visibleText(core.prepareArticleView(article("Elevators"))), XKCD_TITLE_TEXT)
    }

    private companion object {
        const val XKCD_TITLE_TEXT = "\"A reusable elevator would also be great because we'd save so much on the bulky and " +
            "expensive shields that people currently use to avoid bruises and rug burns when sliding down the stairs.\""
        const val DINOSAUR_TITLE_TEXT = "he gets around on a tricked-out horse he calls \"the Holmesmobile\""
        const val DINOSAUR_MAIL_SUBJECT = "\"deduce\" truly is the perfect verb, capable of doing anything. i've deduced this just now"
    }
}

/** Feed Content captured from a real Feed, in `src/test/resources/fixtures`. */
fun fixture(name: String): String =
    checkNotNull(FeedSettingsTest::class.java.getResource("/fixtures/$name")) { "no fixture $name" }.readText()

/** What the reader sees of [document] as text, whitespace collapsed; attributes such as `title` are not visible. */
fun visibleText(document: String): String = Jsoup.parse(document).body().text()

/** The visible text that comes after the first element matching [cssQuery], in reading order. */
fun textAfter(document: String, cssQuery: String): String {
    val body = Jsoup.parse(document).body()
    val start = checkNotNull(body.selectFirst(cssQuery)) { "no $cssQuery in the document" }
    val text = StringBuilder()
    var passed = false
    NodeTraversor.traverse(NodeVisitor { node, _ ->
        if (node === start) passed = true else if (passed && node is TextNode) text.append(node.text())
    }, body)
    return text.toString().replace(Regex("\\s+"), " ")
}

private fun String.occurrences(part: String): Int = windowed(part.length).count { it == part }
