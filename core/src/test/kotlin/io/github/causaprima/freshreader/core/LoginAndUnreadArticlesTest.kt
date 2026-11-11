package io.github.causaprima.freshreader.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LoginAndUnreadArticlesTest {
    private val server = FakeFreshRss().start()
    private val secretBox = TestSecretBox()

    @TempDir
    lateinit var storeDir: File

    private val cores = mutableListOf<Core>()

    private fun core() = Core(storeDir = storeDir, secretBox = secretBox).also(cores::add)

    @AfterEach
    fun tearDown() {
        cores.forEach(Core::close)
        server.close()
    }

    @Test
    fun `logging in with the API password shows the unread Articles, oldest first`() = runBlocking<Unit> {
        server.addArticle("Newest", feedTitle = "Golem", published = 1_800_000_300)
        server.addArticle("Already read", feedTitle = "xkcd", published = 1_800_000_100, read = true)
        server.addArticle("Oldest", feedTitle = "xkcd", published = 1_800_000_000)
        server.addArticle("Middle", feedTitle = "Golem", published = 1_800_000_200)

        val core = core()
        assertEquals(Outcome.Ok, core.logIn(server.url, "reader", "api-secret"))
        assertEquals(Outcome.Ok, core.refresh())

        assertEquals(
            listOf("Oldest" to "xkcd", "Middle" to "Golem", "Newest" to "Golem"),
            core.articles.map { it.title to it.feedTitle },
        )
        val streamRequests = server.requests.filter { "stream/contents" in it.url.encodedPath }
        assert(streamRequests.isNotEmpty())
        streamRequests.forEach {
            assertEquals("GoogleLogin auth=${server.authToken}", it.headers["Authorization"])
        }
    }

    @Test
    fun `unread Articles are loaded across all continuation pages`() = runBlocking<Unit> {
        server.maxPageSize = 2
        (1..5).forEach { server.addArticle("A$it", published = 1_800_000_000L + it) }
        server.addArticle("Read", published = 1_800_000_010, read = true)

        val core = core()
        assertEquals(Outcome.Ok, core.logIn(server.url, "reader", "api-secret"))
        assertEquals(Outcome.Ok, core.refresh())

        assertEquals(listOf("A1", "A2", "A3", "A4", "A5"), core.articles.map { it.title })
        assertTrue(server.requests.count { "stream/contents" in it.url.encodedPath } >= 3)
    }

    @Test
    fun `a wrong API password is reported and nothing is remembered`() = runBlocking<Unit> {
        val core = core()
        assertEquals(Outcome.WrongCredentials, core.logIn(server.url, "reader", "wrong"))
        assertFalse(core.hasStoredCredentials)
        assertFalse(core().hasStoredCredentials)
    }

    @Test
    fun `an invalid Server URL is reported and nothing is remembered`() = runBlocking<Unit> {
        val core = core()
        assertEquals(Outcome.InvalidServerUrl, core.logIn("freshrss.example.org", "reader", "api-secret"))
        assertFalse(core.hasStoredCredentials)
    }

    @Test
    fun `after a successful login the credentials survive a restart`() = runBlocking<Unit> {
        server.addArticle("Only", published = 1_800_000_000)
        assertEquals(Outcome.Ok, core().logIn(server.url, "reader", "api-secret"))

        val restarted = core()
        assertTrue(restarted.hasStoredCredentials)
        assertEquals(Outcome.Ok, restarted.refresh())
        assertEquals(listOf("Only"), restarted.articles.map { it.title })
    }

    @Test
    fun `an unreachable Server at start is reported and a retry succeeds once it is back`() = runBlocking<Unit> {
        server.addArticle("Only", published = 1_800_000_000)
        assertEquals(Outcome.Ok, core().logIn(server.url, "reader", "api-secret"))

        server.reachable = false
        val restarted = core()
        assertIs<Outcome.Unreachable>(restarted.refresh())
        assertEquals(emptyList(), restarted.articles)

        server.reachable = true
        assertEquals(Outcome.Ok, restarted.refresh())
        assertEquals(listOf("Only"), restarted.articles.map { it.title })
    }

    @Test
    fun `losing the connection during a refresh keeps the previous snapshot`() = runBlocking<Unit> {
        server.addArticle("First", published = 1_800_000_000)
        val core = core()
        core.logIn(server.url, "reader", "api-secret")
        assertEquals(Outcome.Ok, core.refresh())

        server.addArticle("Second", published = 1_800_000_001)
        server.reachable = false
        assertIs<Outcome.Unreachable>(core.refresh())
        assertEquals(listOf("First"), core.articles.map { it.title })
    }

    @Test
    fun `a Server answering with something other than Google Reader JSON is reported as a Server error`() =
        runBlocking<Unit> {
            val core = core()
            core.logIn(server.url, "reader", "api-secret")
            server.garbledStream = true
            assertIs<Outcome.ServerError>(core.refresh())
        }

    @Test
    fun `the API password is not stored in plain text`() = runBlocking<Unit> {
        assertEquals(Outcome.Ok, core().logIn(server.url, "reader", "api-secret"))

        val stored = storeDir.walk().filter { it.isFile }.toList()
        assertTrue(stored.isNotEmpty())
        stored.forEach { assertFalse("api-secret".toByteArray().isIn(it.readBytes()), "plain password in ${it.name}") }
    }

    private fun ByteArray.isIn(haystack: ByteArray) =
        (0..haystack.size - size).any { i -> indices.all { haystack[i + it] == this[it] } }

    @Test
    fun `a user name FreshRSS would not accept is reported as wrong credentials`() = runBlocking<Unit> {
        val core = core()
        assertEquals(Outcome.WrongCredentials, core.logIn(server.url, "the reader", "api-secret"))
        assertFalse(core.hasStoredCredentials)
    }
}
