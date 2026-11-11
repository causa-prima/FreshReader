package io.github.causaprima.freshreader.core

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ReadRuleTest : LoggedInCoreTest() {
    override fun newCore() = newCore(firstRetryDelay = 10.milliseconds)

    private fun newCore(firstRetryDelay: Duration) =
        Core(storeDir = storeDir, secretBox = secretBox, firstRetryDelay = firstRetryDelay)

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

        core.openArticle(core.articles[2]) // Pictures 1

        assertEquals(listOf("News 1", "News 2", "Pictures 1"), readTitles())
        assertEquals(listOf("News 1", "News 2", "Pictures 1", "Pictures 2"), core.articles.map { it.title }, "read Articles stay")
        assertEquals(listOf(0, 1), core.sections.map { it.unreadCount })
    }

    @Test
    fun `the read marks reach the Server with exactly the newly read Article ids`() = runBlocking<Unit> {
        addNewsAndPictures()

        core.openArticle(core.articles[2])
        core.awaitSynced()

        assertEquals(listOf("News 1", "News 2", "Pictures 1"), server.markedRead.sorted())
    }

    @Test
    fun `opening an earlier or the same Article again sends nothing new`() = runBlocking<Unit> {
        addNewsAndPictures()
        core.openArticle(core.articles[2])
        core.awaitSynced()

        core.openArticle(core.articles[0])
        core.openArticle(core.articles[2])
        assertEquals(SyncStatus.Synced, core.syncStatus.value)
        core.openArticle(core.articles[3])
        core.awaitSynced()

        assertEquals(listOf("News 1", "News 2", "Pictures 1", "Pictures 2"), server.markedRead.sorted(), "each id sent once")
        assertEquals(2, server.editTagRequests)
    }

    @Test
    fun `an unavailable Server is retried without any visible interruption`() = runBlocking<Unit> {
        addNewsAndPictures()
        server.editTagStatus = 503

        core.openArticle(core.articles[1])
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

        core.openArticle(core.articles[1])
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
        core.openArticle(core.articles[1])
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
        core.openArticle(core.articles[1])

        assertEquals(Outcome.Ok, core.refresh())

        assertEquals(listOf("Pictures 1", "Pictures 2"), core.articles.map { it.title })
    }

    @Test
    fun `a permanent failure surfaces in the sync status, and the read marks are kept for later`() =
        runBlocking<Unit> {
            addNewsAndPictures()
            server.apiPassword = "changed on the Server"

            core.openArticle(core.articles[1])

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
        core.openArticle(core.articles[1])
        withTimeout(5.seconds) { while (server.editTagRequests < 1) delay(5) }
        server.editTagStatus = null

        assertEquals(Outcome.Ok, core.refresh())
        core.awaitSynced()

        assertEquals(listOf("News 1", "News 2"), server.markedRead.sorted())
    }

    @Test
    fun `the read rule follows the opened Article, also when a refresh has moved it in the list`() = runBlocking<Unit> {
        server.addFeed("Golem.de", category = "News")
        server.addArticle("News 1", feedTitle = "Golem.de", published = 1_800_000_000)
        server.addArticle("News 2", feedTitle = "Golem.de", published = 1_800_000_100)
        assertEquals(Outcome.Ok, core.refresh())
        val news2 = core.articles[1]
        // Meanwhile News 1 is read elsewhere and News 3 arrives: News 2 moves up, News 3 takes its place.
        server.readElsewhere("News 1")
        server.addArticle("News 3", feedTitle = "Golem.de", published = 1_800_000_200)
        assertEquals(Outcome.Ok, core.refresh())

        core.openArticle(news2)
        core.awaitSynced()

        assertEquals(listOf("News 2"), server.markedRead)
    }

    @Test
    fun `opening an Article that a refresh has dropped from the list marks nothing`() = runBlocking<Unit> {
        addNewsAndPictures()
        val news1 = core.articles[0]
        server.readElsewhere("News 1")
        assertEquals(Outcome.Ok, core.refresh())

        core.openArticle(news1)

        assertEquals(emptyList(), readTitles())
        assertEquals(SyncStatus.Synced, core.syncStatus.value)
    }

    @Test
    fun `the list snapshot is published whenever it changes, as when an Article is read`() = runBlocking<Unit> {
        addNewsAndPictures()
        val withRead = async { core.snapshot.first { sections -> sections.any { it.unreadCount < it.articles.size } } }

        core.openArticle(core.articles[0])

        assertEquals(listOf(1, 2), withTimeout(5.seconds) { withRead.await() }.map { it.unreadCount })
    }
}
