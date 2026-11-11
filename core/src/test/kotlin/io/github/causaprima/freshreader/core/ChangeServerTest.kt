package io.github.causaprima.freshreader.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class ChangeServerTest {
    private val oldServer = FakeFreshRss().start()
    private val newServer = FakeFreshRss().start()

    @TempDir
    lateinit var storeDir: File

    private val secretBox = TestSecretBox()
    private lateinit var core: Core

    private fun newCore() = Core(storeDir = storeDir, secretBox = secretBox, firstRetryDelay = 10.milliseconds)

    @BeforeEach
    fun readOnTheOldServer() = runBlocking<Unit> {
        oldServer.addFeed("Golem.de", category = "News")
        oldServer.addArticle("Old 1", feedTitle = "Golem.de", published = 1_800_000_000)
        oldServer.addArticle("Old 2", feedTitle = "Golem.de", published = 1_800_000_100)
        newServer.addFeed("Golem.de", category = "News")
        newServer.addArticle("New 1", feedTitle = "Golem.de", published = 1_800_000_200)
        core = newCore()
        assertEquals(Outcome.Ok, core.logIn(oldServer.url, "reader", "api-secret"))
        assertEquals(Outcome.Ok, core.refresh())
        oldServer.editTagStatus = 503 // the read marks stay pending
        core.openArticle(core.articles[1])
        assertEquals(SyncStatus.Pending(2), core.syncStatus.value)
    }

    @AfterEach
    fun tearDown() {
        core.close()
        oldServer.close()
        newServer.close()
    }

    @Test
    fun `logging in to another Server drops the read marks not yet sent and shows that Server's Articles`() =
        runBlocking<Unit> {
            assertEquals(Outcome.Ok, core.logIn(newServer.url, "reader", "api-secret"))
            assertEquals(Outcome.Ok, core.refresh())

            assertEquals(SyncStatus.Synced, core.syncStatus.value)
            assertEquals(listOf("New 1"), core.articles.map { it.title })
            oldServer.editTagStatus = null
            delay(200) // retries would have happened by now
            assertEquals(emptyList(), oldServer.markedRead + newServer.markedRead)
            assertEquals(0, newServer.editTagRequests)

            core.close() // the app is restarted
            core = newCore()
            assertEquals(SyncStatus.Synced, core.syncStatus.value)
        }

    @Test
    fun `logging in as another user on the same Server drops the read marks not yet sent`() = runBlocking<Unit> {
        oldServer.username = "partner"

        assertEquals(Outcome.Ok, core.logIn(oldServer.url, "partner", "api-secret"))

        assertEquals(SyncStatus.Synced, core.syncStatus.value)
        oldServer.editTagStatus = null
        delay(200)
        assertEquals(emptyList(), oldServer.markedRead)
    }

    @Test
    fun `logging in again to the same Server as the same user keeps the read marks not yet sent, and sends them`() =
        runBlocking<Unit> {
            assertEquals(Outcome.Ok, core.logIn(oldServer.url, "reader", "api-secret"))

            assertEquals(SyncStatus.Pending(2), core.syncStatus.value)
            oldServer.editTagStatus = null
            core.awaitSynced()
            assertEquals(setOf("Old 1", "Old 2"), oldServer.markedRead.toSet())
        }

    @Test
    fun `the current Server and user are offered for changing them, and a failed change keeps them`() =
        runBlocking<Unit> {
            assertEquals(Account(oldServer.url, "reader"), core.account)

            assertEquals(Outcome.WrongCredentials, core.logIn(newServer.url, "reader", "mistyped"))
            assertEquals(Outcome.InvalidServerUrl, core.logIn("not a url", "reader", "api-secret"))
            core.close() // the app is restarted
            core = newCore()

            assertEquals(Account(oldServer.url, "reader"), core.account)
            assertEquals(SyncStatus.Pending(2), core.syncStatus.value)
            oldServer.editTagStatus = null
            assertEquals(Outcome.Ok, core.refresh())
            core.awaitSynced()
            assertEquals(listOf("Old 1", "Old 2"), oldServer.markedRead.sorted())
        }

    @Test
    fun `the Reading Order, Category Colours and Feed Settings stay when the Server changes`() = runBlocking<Unit> {
        val feedId = core.articles.first().feedId
        core.setReadingOrder(listOf("Pictures", "News"))
        core.setFeedSettings(feedId, FeedSettings(viewMode = ViewMode.ReaderView, bonusText = true))
        core.setCategoryColour("News", CategoryColour.Teal)
        newServer.addFeed("xkcd", category = "Pictures")
        newServer.addArticle("New comic", feedTitle = "xkcd", published = 1_800_000_300)

        assertEquals(Outcome.Ok, core.logIn(newServer.url, "reader", "api-secret"))
        assertEquals(Outcome.Ok, core.refresh())

        assertEquals(listOf("Pictures", "News"), core.sections.map { it.category })
        assertEquals(FeedSettings(viewMode = ViewMode.ReaderView, bonusText = true), core.feedSettings(feedId))
        assertEquals(CategoryColour.Teal, core.categoryColour("News"))
    }
}
