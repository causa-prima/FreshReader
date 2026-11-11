package io.github.causaprima.freshreader.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A Feed whose favicon the Server only has as SVG, which Android cannot show, gets its site's other favicon.
 * Like FreshRSS releases: FreshRSS picks GitHub's SVG, while github.com also lists a PNG.
 */
class FaviconFallbackTest {
    private val server = FakeFreshRss().start()
    private val site = FakeSite().start()

    @TempDir
    lateinit var storeDir: File

    private val clock = MutableClock(Instant.parse("2026-11-10T12:00:00Z"))
    private lateinit var core: Core

    @BeforeEach
    fun subscribe() {
        server.addFeed("FreshRSS releases", category = "Updates", icon = SVG, site = site.url("/FreshRSS/releases"))
        server.addArticle("FreshRSS 1.28.0", feedTitle = "FreshRSS releases", published = 1_800_000_000)
    }

    @AfterEach
    fun tearDown() {
        core.close()
        server.close()
        site.close()
    }

    /** Starts the app (again, keeping what it stored) and loads the list. */
    private fun start() = runBlocking<Unit> {
        if (::core.isInitialized) core.close()
        core = Core(storeDir = storeDir, secretBox = TestSecretBox(), clock = clock)
        if (core.account == null) assertEquals(Outcome.Ok, core.logIn(server.url, "reader", "api-secret"))
        assertEquals(Outcome.Ok, core.refresh())
    }

    private suspend fun favicon() = core.favicon(core.articles.single())

    @Test
    fun `an SVG favicon is replaced by the site's first favicon that is not SVG`() = runBlocking<Unit> {
        site.page(
            "/FreshRSS/releases",
            """
            <html><head>
              <link rel="mask-icon" href="/pinned-octocat.svg" color="#000000">
              <link rel="icon" type="image/svg+xml" href="/favicon.svg">
              <link rel="alternate icon" type="image/png" href="/favicons/favicon.png">
              <link rel="icon" href="/favicons/second.png">
            </head><body>Releases</body></html>
            """.trimIndent(),
        )
        site.file("/favicons/favicon.png", PNG, "image/png")
        site.file("/favicons/second.png", OTHER_PNG, "image/png")
        start()

        assertContentEquals(PNG, favicon())
    }

    @Test
    fun `the fallback survives a restart and is not fetched again within a month`() = runBlocking<Unit> {
        githubLike()
        start()
        assertContentEquals(PNG, favicon())

        site.reachable = false
        clock.now = clock.now.plus(Duration.ofDays(29))
        start()

        assertContentEquals(PNG, favicon())
        assertEquals(listOf("/FreshRSS/releases" to 200, "/favicons/favicon.png" to 200), site.responses)
    }

    @Test
    fun `after a month the fallback is checked again conditionally, and kept if unchanged`() = runBlocking<Unit> {
        githubLike()
        start()
        favicon()
        site.responses.clear()

        clock.now = clock.now.plus(Duration.ofDays(31))
        start()

        assertContentEquals(PNG, favicon())
        assertEquals(listOf("/favicons/favicon.png" to 304), site.responses)
    }

    @Test
    fun `after a month a changed fallback replaces the kept one`() = runBlocking<Unit> {
        githubLike()
        start()
        favicon()
        site.file("/favicons/favicon.png", OTHER_PNG, "image/png")

        monthLater()

        assertContentEquals(OTHER_PNG, favicon())
    }

    @Test
    fun `after a month a fallback that no longer exists is determined anew`() = runBlocking<Unit> {
        githubLike()
        start()
        favicon()
        site.remove("/favicons/favicon.png")
        site.file("/favicon.ico", ICO, "image/x-icon")

        monthLater()

        assertContentEquals(ICO, favicon())
    }

    @Test
    fun `after a month the fallback stays while the site cannot be reached, and is checked once it can`() =
        runBlocking<Unit> {
            githubLike()
            start()
            favicon()
            site.reachable = false

            monthLater()
            assertContentEquals(PNG, favicon())

            site.reachable = true
            site.responses.clear()
            start()
            assertContentEquals(PNG, favicon())
            assertEquals(listOf("/favicons/favicon.png" to 304), site.responses)
        }

    @Test
    fun `a site without a fallback is not loaded again within a month`() = runBlocking<Unit> {
        site.page("/FreshRSS/releases", """<html><head><link rel="icon" href="/favicon.svg"></head></html>""")
        start()
        assertNull(favicon())
        site.responses.clear()
        site.file("/favicon.ico", ICO, "image/x-icon")

        clock.now = clock.now.plus(Duration.ofDays(29))
        start()
        assertNull(favicon())
        assertEquals(emptyList(), site.responses)

        clock.now = clock.now.plus(Duration.ofDays(2))
        start()
        assertContentEquals(ICO, favicon())
    }

    @Test
    fun `a favicon from the Server that is not SVG drops the fallback`() = runBlocking<Unit> {
        githubLike()
        start()
        favicon()

        server.feeds.single().icon = ICO
        start()
        assertContentEquals(ICO, favicon())

        server.feeds.single().icon = SVG // FreshRSS picked the SVG again
        site.reachable = false
        start()
        assertNull(favicon(), "the fallback is gone, and the site cannot be asked for a new one")
    }

    @Test
    fun `a site that cannot be reached when its fallback is first needed is asked again the next time`() =
        runBlocking<Unit> {
            githubLike()
            site.reachable = false
            start()
            assertNull(favicon())

            site.reachable = true

            assertContentEquals(PNG, favicon())
        }

    @Test
    fun `after a month a fallback the site only dates is checked again conditionally too`() = runBlocking<Unit> {
        githubLike()
        site.file("/favicons/favicon.png", PNG, "image/png", datedOnly = true)
        start()
        favicon()
        site.responses.clear()

        monthLater()

        assertContentEquals(PNG, favicon())
        assertEquals(listOf("/favicons/favicon.png" to 304), site.responses)
    }

    private fun monthLater() {
        clock.now = clock.now.plus(Duration.ofDays(31))
        start()
    }

    /** The site lists an SVG and a PNG favicon, like github.com. */
    private fun githubLike() {
        site.page(
            "/FreshRSS/releases",
            """<html><head><link rel="icon" type="image/svg+xml" href="/favicon.svg"><link rel="alternate icon" type="image/png" href="/favicons/favicon.png"></head></html>""",
        )
        site.file("/favicons/favicon.png", PNG, "image/png")
    }

    private companion object {
        val SVG = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16"><circle r="8"/></svg>""".encodeToByteArray()
        val PNG = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1)
        val ICO = byteArrayOf(0, 0, 1, 0, 1)
        val OTHER_PNG = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 2)
    }
}
