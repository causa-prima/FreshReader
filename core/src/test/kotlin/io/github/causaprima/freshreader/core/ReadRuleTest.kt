package io.github.causaprima.freshreader.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ReadRuleTest {
    private val server = FakeFreshRss().start()

    @TempDir
    lateinit var storeDir: File

    private val secretBox = TestSecretBox()
    private lateinit var core: Core

    @BeforeEach
    fun logIn() = runBlocking<Unit> {
        core = newCore()
        assertEquals(Outcome.Ok, core.logIn(server.url, "reader", "api-secret"))
    }

    @AfterEach
    fun tearDown() {
        core.close()
        server.close()
    }

    private fun newCore(firstRetryDelay: Duration = 10.milliseconds) =
        Core(storeDir = storeDir, secretBox = secretBox, firstRetryDelay = firstRetryDelay)

    private suspend fun Core.awaitSyncStatus(predicate: (SyncStatus) -> Boolean): SyncStatus =
        withTimeout(5.seconds) { syncStatus.first(predicate) }

    private suspend fun Core.awaitSynced() = awaitSyncStatus { it == SyncStatus.Synced }

    /** Two Categories in Reading Order News, Pictures; the list reads News 1, News 2, Pictures 1, Pictures 2. */
    private fun addNewsAndPictures() = runBlocking<Unit> {
        server.addFeed("Golem.de", category = "News")
        server.addFeed("xkcd", category = "Pictures")
        server.addArticle("News 1", feedTitle = "Golem.de", published = 1_800_000_000)
        server.addArticle("Pictures 1", feedTitle = "xkcd", published = 1_800_000_100)
        server.addArticle("News 2", feedTitle = "Golem.de", published = 1_800_000_200)
        server.addArticle("Pictures 2", feedTitle = "xkcd", published = 1_800_000_300)
        core.setReadingOrder(listOf("News", "Pictures"))
        assertEquals(Outcome.Ok, core.refresh())
    }

    private fun readTitles() = core.articles.filter { it.read }.map { it.title }

    @Test
    fun `opening an Article greys out it and all earlier Articles, across Categories`() = runBlocking<Unit> {
        addNewsAndPictures()

        core.openArticle(2) // Pictures 1

        assertEquals(listOf("News 1", "News 2", "Pictures 1"), readTitles())
        assertEquals(listOf("News 1", "News 2", "Pictures 1", "Pictures 2"), core.articles.map { it.title }, "read Articles stay")
        assertEquals(listOf(0, 1), core.sections.map { it.unreadCount })
    }

    @Test
    fun `the read marks reach the Server with exactly the newly read Article ids`() = runBlocking<Unit> {
        addNewsAndPictures()

        core.openArticle(2)
        core.awaitSynced()

        assertEquals(listOf("News 1", "News 2", "Pictures 1"), server.markedRead.sorted())
    }

    @Test
    fun `opening an earlier or the same Article again sends nothing new`() = runBlocking<Unit> {
        addNewsAndPictures()
        core.openArticle(2)
        core.awaitSynced()

        core.openArticle(0)
        core.openArticle(2)
        assertEquals(SyncStatus.Synced, core.syncStatus.value)
        core.openArticle(3)
        core.awaitSynced()

        assertEquals(listOf("News 1", "News 2", "Pictures 1", "Pictures 2"), server.markedRead.sorted(), "each id sent once")
        assertEquals(2, server.editTagRequests)
    }

    @Test
    fun `an unavailable Server is retried without any visible interruption`() = runBlocking<Unit> {
        addNewsAndPictures()
        server.editTagStatus = 503

        core.openArticle(1)
        withTimeout(5.seconds) { while (server.editTagRequests < 3) delay(5) }
        assertEquals(SyncStatus.Pending(2), core.syncStatus.value)
        server.editTagStatus = null
        core.awaitSynced()

        assertEquals(listOf("News 1", "News 2"), server.markedRead.sorted())
    }

    @Test
    fun `an unreachable Server is retried without any visible interruption`() = runBlocking<Unit> {
        addNewsAndPictures()
        server.reachable = false

        core.openArticle(1)
        delay(200) // several retries
        assertEquals(SyncStatus.Pending(2), core.syncStatus.value)
        server.reachable = true
        core.awaitSynced()

        assertEquals(listOf("News 1", "News 2"), server.markedRead.sorted())
    }

    @Test
    fun `pending read marks are sent after the app is restarted`() = runBlocking<Unit> {
        addNewsAndPictures()
        server.reachable = false
        core.openArticle(1)
        core.close() // the app is killed
        server.reachable = true

        core = newCore()
        assertEquals(SyncStatus.Pending(2), core.syncStatus.value)
        assertEquals(Outcome.Ok, core.refresh())
        core.awaitSynced()

        assertEquals(listOf("News 1", "News 2"), server.markedRead.sorted())
    }

    @Test
    fun `Articles whose read marks are still pending do not come back as unread on refresh`() = runBlocking<Unit> {
        addNewsAndPictures()
        server.editTagStatus = 503
        core.openArticle(1)

        assertEquals(Outcome.Ok, core.refresh())

        assertEquals(listOf("Pictures 1", "Pictures 2"), core.articles.map { it.title })
    }

    @Test
    fun `a permanent failure surfaces in the sync status, and the read marks are kept for later`() =
        runBlocking<Unit> {
            addNewsAndPictures()
            server.apiPassword = "changed on the Server"

            core.openArticle(1)

            assertEquals(SyncStatus.Failed(2, Outcome.WrongCredentials), core.awaitSyncStatus { it is SyncStatus.Failed })
            assertEquals(emptyList(), server.markedRead)

            // The reader logs in with the new API password; the app refreshes.
            assertEquals(Outcome.Ok, core.logIn(server.url, "reader", "changed on the Server"))
            assertEquals(Outcome.Ok, core.refresh())
            core.awaitSynced()
            assertEquals(listOf("News 1", "News 2"), server.markedRead.sorted())
        }

    @Test
    fun `a refresh retries pending read marks at once instead of waiting for the next retry`() = runBlocking<Unit> {
        core.close()
        core = newCore(firstRetryDelay = 1.hours)
        addNewsAndPictures()
        server.editTagStatus = 503
        core.openArticle(1)
        withTimeout(5.seconds) { while (server.editTagRequests < 1) delay(5) }
        server.editTagStatus = null

        assertEquals(Outcome.Ok, core.refresh())
        core.awaitSynced()

        assertEquals(listOf("News 1", "News 2"), server.markedRead.sorted())
    }
}
