package io.github.causaprima.freshreader.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class RefreshAndMarkAllTest {
    private val server = FakeFreshRss().start()

    @TempDir
    lateinit var storeDir: File

    private lateinit var core: Core

    @BeforeEach
    fun logIn() = runBlocking<Unit> {
        core = Core(storeDir = storeDir, secretBox = TestSecretBox(), firstRetryDelay = 10.milliseconds)
        assertEquals(Outcome.Ok, core.logIn(server.url, "reader", "api-secret"))
        server.addFeed("Golem.de", category = "News")
    }

    @AfterEach
    fun tearDown() {
        core.close()
        server.close()
    }

    private fun addNews(title: String, published: Long) = server.addArticle(title, feedTitle = "Golem.de", published = published)

    private fun titles() = core.articles.map { it.title }

    @Test
    fun `a refresh loads new unread Articles and drops read ones`() = runBlocking<Unit> {
        addNews("First", published = 1_800_000_000)
        addNews("Second", published = 1_800_000_100)
        assertEquals(Outcome.Ok, core.refresh())
        core.openArticle(0)
        core.awaitSynced()
        addNews("Third", published = 1_800_000_200)

        assertEquals(Outcome.Ok, core.refresh())

        assertEquals(listOf("Second", "Third"), titles())
    }

    @Test
    fun `without a refresh the list never changes, even when the Server has new Articles`() = runBlocking<Unit> {
        addNews("First", published = 1_800_000_000)
        addNews("Second", published = 1_800_000_100)
        assertEquals(Outcome.Ok, core.refresh())

        addNews("Arrived earlier", published = 1_700_000_000)
        server.articles[1] = server.articles[1].copy(read = true) // "Second", read on another device
        core.openArticle(0)
        core.awaitSynced()

        assertEquals(listOf("First", "Second"), titles())
    }

    @Test
    fun `Mark all as read sends exactly the snapshot's unread ids, and later Articles stay unread`() = runBlocking<Unit> {
        addNews("First", published = 1_800_000_000)
        addNews("Second", published = 1_800_000_100)
        addNews("Third", published = 1_800_000_200)
        assertEquals(Outcome.Ok, core.refresh())
        core.openArticle(0)
        core.awaitSynced()
        addNews("Arrived later", published = 1_800_000_300)

        core.markAllAsRead()
        assertEquals(listOf(true, true, true), core.articles.map { it.read })
        core.awaitSynced()

        assertEquals(listOf("First", "Second", "Third"), server.markedRead.sorted(), "each snapshot id once")
        assertEquals(Outcome.Ok, core.refresh())
        assertEquals(listOf("Arrived later"), titles())
    }
}
