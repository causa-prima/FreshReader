package io.github.causaprima.freshreader.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals

class ReadingOrderTest {
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
    fun stopServer() = server.close()

    /** One unread Article in each of the reader's five Categories. */
    private fun addDailyCategories() {
        for (category in listOf("Job", "Leisure", "News", "Pictures", "Updates")) {
            server.addFeed("$category feed", category = category)
            server.addArticle("$category article", feedTitle = "$category feed", published = 1_800_000_000)
        }
    }

    private fun sectionOrder(): List<String> = core.sections.map { it.category }

    @Test
    fun `the sections follow the Reading Order`() = runBlocking<Unit> {
        addDailyCategories()

        core.setReadingOrder(listOf("News", "Updates", "Job", "Pictures", "Leisure"))
        assertEquals(Outcome.Ok, core.refresh())

        assertEquals(listOf("News", "Updates", "Job", "Pictures", "Leisure"), sectionOrder())
    }

    @Test
    fun `a new Reading Order rearranges the current snapshot without a refresh`() = runBlocking<Unit> {
        addDailyCategories()
        assertEquals(Outcome.Ok, core.refresh())
        server.reachable = false

        core.setReadingOrder(listOf("Pictures", "News"))

        assertEquals(listOf("Pictures", "News", "Job", "Leisure", "Updates"), sectionOrder())
    }

    @Test
    fun `the Reading Order survives a restart`() = runBlocking<Unit> {
        addDailyCategories()
        core.setReadingOrder(listOf("News", "Updates", "Job", "Pictures", "Leisure"))

        val restarted = Core(storeDir = storeDir, secretBox = secretBox)
        assertEquals(Outcome.Ok, restarted.refresh())

        assertEquals(listOf("News", "Updates", "Job", "Pictures", "Leisure"), restarted.sections.map { it.category })
    }

    @Test
    fun `without a Reading Order all Categories are offered alphabetically, including those with nothing unread`() =
        runBlocking<Unit> {
            server.addFeed("Golem.de", category = "News")
            server.addFeed("Stellenmarkt", category = "Job")
            server.addFeed("xkcd", category = "pictures")
            server.addArticle("Headline", feedTitle = "Golem.de", published = 1_800_000_000)
            server.addArticle("Old job ad", feedTitle = "Stellenmarkt", published = 1_800_000_000, read = true)

            assertEquals(Outcome.Ok, core.refresh())

            assertEquals(listOf("Job", "News", "pictures"), core.categories)
        }

    @Test
    fun `a renamed or new Category comes after the ordered ones, alphabetically`() = runBlocking<Unit> {
        core.setReadingOrder(listOf("News", "Updates", "Job", "Pictures", "Leisure"))
        // "Pictures" was renamed to "Comics" on the Server, "Gaming" is new.
        for (category in listOf("Leisure", "Gaming", "News", "Comics", "Job", "Updates")) {
            server.addFeed("$category feed", category = category)
            server.addArticle("$category article", feedTitle = "$category feed", published = 1_800_000_000)
        }

        assertEquals(Outcome.Ok, core.refresh())

        val expected = listOf("News", "Updates", "Job", "Leisure", "Comics", "Gaming")
        assertEquals(expected, sectionOrder())
        assertEquals(expected, core.categories)
    }
}
