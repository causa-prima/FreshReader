package io.github.causaprima.freshreader.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What a list entry shows besides its title: the Feed's favicon. */
class FaviconTest {
    private val server = FakeFreshRss().start()

    @TempDir
    lateinit var storeDir: File

    private lateinit var core: Core

    @AfterEach
    fun tearDown() {
        core.close()
        server.close()
    }

    private fun logIn(now: Instant = NOW) = runBlocking<Unit> {
        core = Core(storeDir = storeDir, secretBox = TestSecretBox(), clock = Clock.fixed(now, ZoneOffset.UTC))
        assertEquals(Outcome.Ok, core.logIn(server.url, "reader", "api-secret"))
        assertEquals(Outcome.Ok, core.refresh())
    }

    private fun article(title: String) = core.articles.single { it.title == title }

    @Test
    fun `an entry shows its Feed's favicon, as the Server delivers it`() = runBlocking<Unit> {
        server.addFeed("xkcd.com", category = "Pictures", icon = XKCD_ICON)
        server.addArticle("Elevators", feedTitle = "xkcd.com", published = NOW.epochSecond)
        logIn()

        assertContentEquals(XKCD_ICON, core.favicon(article("Elevators")))
    }

    @Test
    fun `a Feed without a favicon of its own gets none, so that the entry can show a placeholder`() =
        runBlocking<Unit> {
            server.addFeed("ComputerBase", category = "News", icon = null) // FreshRSS found none: its default icon
            server.addFeed("Golem.de", category = "News", icon = XKCD_ICON, iconListed = false)
            server.addFeed("Der Postillon", category = "Leisure", icon = XKCD_ICON)
            listOf("ComputerBase", "Golem.de", "Der Postillon").forEach {
                server.addArticle("From $it", feedTitle = it, published = NOW.epochSecond)
            }
            logIn()

            assertNull(core.favicon(article("From ComputerBase")), "the Server's default favicon")
            assertNull(core.favicon(article("From Golem.de")), "no iconUrl")
            server.reachable = false
            assertNull(core.favicon(article("From Der Postillon")), "the favicon cannot be loaded")
        }

    private companion object {
        val NOW: Instant = Instant.parse("2026-11-06T12:00:00Z")
        val XKCD_ICON = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3)
    }
}
