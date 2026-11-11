package io.github.causaprima.freshreader.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class CategoryColourTest : LoggedInCoreTest() {
    /** One unread Article in each of [categories]. */
    private fun addCategories(vararg categories: String) {
        for (category in categories) {
            server.addFeed("$category feed", category = category)
            server.addArticle("$category article", feedTitle = "$category feed", published = 1_800_000_000)
        }
    }

    private fun sectionColours(): List<CategoryColour> = core.sections.map { core.categoryColour(it.category) }

    @Test
    fun `without a choice each Category gets its own colour, and with more Categories than colours neighbours still differ`() =
        runBlocking<Unit> {
            addCategories(*Array(CategoryColour.entries.size + 3) { "Category %02d".format(it) })

            assertEquals(Outcome.Ok, core.refresh())

            val colours = sectionColours()
            assertEquals(CategoryColour.entries.toSet(), colours.take(CategoryColour.entries.size).toSet())
            colours.zipWithNext().forEach { (above, below) -> assertNotEquals(above, below) }
        }

    @Test
    fun `a chosen colour marks its Category, its neighbours avoid it, and it stays after a restart`() =
        runBlocking<Unit> {
            addCategories("Job", "Leisure", "News")
            assertEquals(Outcome.Ok, core.refresh())
            val ofJob = core.categoryColour("Job")

            core.setCategoryColour("Leisure", ofJob)
            restart()
            assertEquals(Outcome.Ok, core.refresh())

            assertEquals(ofJob, core.categoryColour("Leisure"))
            assertEquals(ofJob, core.chosenCategoryColour("Leisure"))
            assertNull(core.chosenCategoryColour("Job"))
            sectionColours().zipWithNext().forEach { (above, below) -> assertNotEquals(above, below) }
        }

    @Test
    fun `choosing "Automatic" gives a Category its automatic colour back`() = runBlocking<Unit> {
        addCategories("Job", "Leisure", "News")
        assertEquals(Outcome.Ok, core.refresh())
        val automatic = core.categoryColour("Leisure")

        core.setCategoryColour("Leisure", CategoryColour.entries.first { it != automatic })
        core.setCategoryColour("Leisure", null)

        assertEquals(automatic, core.categoryColour("Leisure"))
        assertNull(core.chosenCategoryColour("Leisure"))
    }

    @Test
    fun `a Category keeps its colour when another one has nothing left to read`() = runBlocking<Unit> {
        addCategories("Job", "Leisure", "News")
        assertEquals(Outcome.Ok, core.refresh())
        val ofNews = core.categoryColour("News")

        core.markAllAsRead()
        server.addArticle("More news", feedTitle = "News feed", published = 1_800_000_100)
        assertEquals(Outcome.Ok, core.refresh())

        assertEquals(listOf("News"), core.sections.map { it.category })
        assertEquals(ofNews, core.categoryColour("News"))
    }

    @Test
    fun `choosing a colour leaves the automatic colours of Categories other than its neighbours alone`() =
        runBlocking<Unit> {
            addCategories("Job", "Leisure", "News", "Pictures", "Updates")
            assertEquals(Outcome.Ok, core.refresh())
            val before = core.categories.associateWith(core::categoryColour)

            core.setCategoryColour("News", CategoryColour.entries.last())

            assertEquals(before["Job"], core.categoryColour("Job"))
            assertEquals(before["Updates"], core.categoryColour("Updates"))
        }

    @Test
    fun `the Category below a chosen colour picks another one automatically`() = runBlocking<Unit> {
        addCategories("Job", "Leisure", "News")
        assertEquals(Outcome.Ok, core.refresh())
        val ofLeisure = core.categoryColour("Leisure")

        core.setCategoryColour("Job", ofLeisure)

        assertNotEquals(ofLeisure, core.categoryColour("Leisure"))
    }
}
