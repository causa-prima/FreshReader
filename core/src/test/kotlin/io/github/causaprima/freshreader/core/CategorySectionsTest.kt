package io.github.causaprima.freshreader.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class CategorySectionsTest : LoggedInCoreTest() {
    private fun refreshed(): List<Pair<String, List<String>>> = runBlocking {
        assertEquals(Outcome.Ok, core.refresh())
        core.sections.map { section -> section.category to section.articles.map { it.title } }
    }

    @Test
    fun `Articles are sectioned by their Feed's Category, sections alphabetically, Feeds interleaved oldest first`() {
        server.addFeed("Golem.de", category = "News")
        server.addFeed("tagesschau", category = "News")
        server.addFeed("xkcd", category = "Pictures")
        server.addFeed("FreshRSS releases", category = "Updates")
        server.addArticle("Release 1.27", feedTitle = "FreshRSS releases", published = 1_800_000_050)
        server.addArticle("Golem late", feedTitle = "Golem.de", published = 1_800_000_300)
        server.addArticle("Comic", feedTitle = "xkcd", published = 1_800_000_100)
        server.addArticle("Tagesschau middle", feedTitle = "tagesschau", published = 1_800_000_200)
        server.addArticle("Golem early", feedTitle = "Golem.de", published = 1_800_000_000)

        assertEquals(
            listOf(
                "News" to listOf("Golem early", "Tagesschau middle", "Golem late"),
                "Pictures" to listOf("Comic"),
                "Updates" to listOf("Release 1.27"),
            ),
            refreshed(),
        )
    }

    @Test
    fun `Categories without unread Articles are not shown`() {
        server.addFeed("Golem.de", category = "News")
        server.addFeed("Stellenmarkt", category = "Job")
        server.addFeed("Empty feed", category = "Leisure")
        server.addArticle("Old job ad", feedTitle = "Stellenmarkt", published = 1_800_000_000, read = true)
        server.addArticle("Headline", feedTitle = "Golem.de", published = 1_800_000_100)

        assertEquals(listOf("News" to listOf("Headline")), refreshed())
    }

    @Test
    fun `each section counts its unread Articles`() = runBlocking<Unit> {
        server.addFeed("Golem.de", category = "News")
        server.addFeed("xkcd", category = "Pictures")
        repeat(3) { server.addArticle("News $it", feedTitle = "Golem.de", published = 1_800_000_000L + it) }
        server.addArticle("Read news", feedTitle = "Golem.de", published = 1_800_000_010, read = true)
        server.addArticle("Comic", feedTitle = "xkcd", published = 1_800_000_020)

        assertEquals(Outcome.Ok, core.refresh())
        assertEquals(listOf("News" to 3, "Pictures" to 1), core.sections.map { it.category to it.unreadCount })
    }

    @Test
    fun `sections are ordered alphabetically regardless of case`() {
        server.addFeed("a", category = "Updates")
        server.addFeed("b", category = "Job")
        server.addFeed("c", category = "news")
        server.addArticle("A", feedTitle = "a", published = 1_800_000_000)
        server.addArticle("B", feedTitle = "b", published = 1_800_000_000)
        server.addArticle("C", feedTitle = "c", published = 1_800_000_000)

        assertEquals(listOf("Job", "news", "Updates"), refreshed().map { it.first })
    }

    @Test
    fun `Articles of a Feed missing from the Server's Feed list are still shown`() {
        server.addFeed("Golem.de", category = "News")
        server.addFeed("Just subscribed", category = "Updates", listed = false)
        server.addArticle("Headline", feedTitle = "Golem.de", published = 1_800_000_000)
        server.addArticle("Brand new", feedTitle = "Just subscribed", published = 1_800_000_100)

        assertEquals(
            listOf("News" to listOf("Headline"), "Uncategorized" to listOf("Brand new")),
            refreshed(),
        )
    }

    @Test
    fun `the display order across sections is the order of the sections`() = runBlocking<Unit> {
        server.addFeed("xkcd", category = "Pictures")
        server.addFeed("Golem.de", category = "News")
        server.addArticle("Comic", feedTitle = "xkcd", published = 1_800_000_000)
        server.addArticle("Headline", feedTitle = "Golem.de", published = 1_800_000_100)

        assertEquals(Outcome.Ok, core.refresh())
        assertEquals(listOf("Headline", "Comic"), core.articles.map { it.title })
    }
}
