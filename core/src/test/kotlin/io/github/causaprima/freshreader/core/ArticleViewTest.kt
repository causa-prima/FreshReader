package io.github.causaprima.freshreader.core

import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.util.Locale
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArticleViewTest : LoggedInCoreTest() {
    override fun newCore() =
        Core(storeDir = storeDir, secretBox = secretBox, zone = ZoneId.of("Europe/Berlin"), locale = Locale.GERMANY)

    private fun onlyArticleView(): String = runBlocking {
        assertEquals(Outcome.Ok, core.refresh())
        core.prepareArticleView(core.articles.single())
    }

    @Test
    fun `the Article View shows the Article's title, Feed, publication date and Feed Content`() {
        val feedContent = """<p><img src="https://imgs.xkcd.com/comics/ice_sheets.png" title="Bonus"></p>"""
        server.addFeed("xkcd", category = "Pictures")
        server.addArticle(
            "Ice Sheets", feedTitle = "xkcd",
            published = 1_793_955_600, // 2026-11-06 10:00 in Berlin
            content = feedContent,
        )

        val view = onlyArticleView()

        assertContains(view, "Ice Sheets")
        assertContains(view, "xkcd")
        assertContains(view, "06.11.2026, 10:00")
        assertContains(view, feedContent)
    }

    @Test
    fun `title and Feed are shown as text, never as markup`() {
        server.addFeed("Fish & <i>Chips</i>", category = "News")
        server.addArticle(
            "<script>alert(1)</script> & more", feedTitle = "Fish & <i>Chips</i>",
            published = 1_800_000_000, content = "<p>Text</p>",
        )

        val view = onlyArticleView()

        assertContains(view, "&lt;script&gt;alert(1)&lt;/script&gt; &amp; more")
        assertContains(view, "Fish &amp; &lt;i&gt;Chips&lt;/i&gt;")
        assertFalse("<script>" in view)
    }

    @Test
    fun `title and Feed show the characters FreshRSS hands out as stand-ins, as text`() {
        server.addFeed("Fish ＆ Chips", category = "News")
        server.addArticle(
            "＜b＞Tipp＜/b＞: ＆quot;Merz＆quot;", feedTitle = "Fish ＆ Chips",
            published = 1_800_000_000, content = "<p>Text</p>",
        )

        val header = Jsoup.parse(onlyArticleView()).selectFirst("header")!!

        assertEquals("<b>Tipp</b>: \"Merz\"", header.selectFirst("h1")!!.text())
        assertTrue(header.select("h1 b").isEmpty())
        assertTrue(header.selectFirst(".meta")!!.text().startsWith("Fish & Chips · "))
    }

    @Test
    fun `images embedded over plain http are loaded over https, since http would be blocked`() {
        // As Dinosaur Comics delivers its comic.
        server.addArticle(
            "Comic", feedTitle = "Dinosaur Comics", published = 1_800_000_000,
            content = """<p><img src="http://www.qwantz.com/comics/comic2-5237.png" title="hover"></p>""" +
                """<picture><source srcset='http://example.org/a.webp 1x, http://example.org/b.webp 2x'></picture>""" +
                """<a href="http://www.qwantz.com/">qwantz</a>""",
        )

        val view = onlyArticleView()

        assertContains(view, """<img src="https://www.qwantz.com/comics/comic2-5237.png" title="hover">""")
        assertContains(view, """srcset="https://example.org/a.webp 1x, https://example.org/b.webp 2x"""")
        assertContains(view, """<a href="http://www.qwantz.com/">qwantz</a>""", message = "links are left alone")
    }
}

