package io.github.causaprima.freshreader.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals

/** The link to an Article's Original Page, as used for showing, opening and sharing it. */
class OriginalPageTest {
    private val server = FakeFreshRss().start()
    private val followIt = FakeFollowIt().start()

    @TempDir
    lateinit var storeDir: File

    private lateinit var core: Core

    @BeforeEach
    fun logIn() = runBlocking<Unit> {
        core = Core(storeDir = storeDir, secretBox = TestSecretBox(), httpClient = followIt.client())
        assertEquals(Outcome.Ok, core.logIn(server.url, "reader", "api-secret"))
    }

    @AfterEach
    fun tearDown() {
        core.close()
        server.close()
        followIt.close()
    }

    /** Der Postillon's Feed, as delivered through follow.it: the link is a follow.it tracking link. */
    private fun addPostillon(): Article = runBlocking {
        followIt.redirect(POSTILLON_STORY_PATH, POSTILLON_REDIRECT)
        server.addFeed("Der Postillon - News des Tages", category = "Leisure")
        server.addArticle(
            "Bahnservice für Eilige: 1.-Klasse-Wagen fahren künftig 10 km/h schneller",
            feedTitle = "Der Postillon - News des Tages", published = 1_793_989_276,
            content = fixture("postillon-1-klasse.html"),
            url = "https://api.follow.it$POSTILLON_STORY_PATH?utm_source=follow.it",
        )
        assertEquals(Outcome.Ok, core.refresh())
        core.articles.single()
    }

    @Test
    fun `a follow_it tracking link leads to the Article's real page`() = runBlocking<Unit> {
        val article = addPostillon()

        assertEquals("https://www.der-postillon.com/2016/10/1.-klasse-schneller.html", core.originalPageUrl(article))
    }

    @Test
    fun `other links are used as they are, without asking follow_it`() = runBlocking<Unit> {
        server.addArticle("Elevators", feedTitle = "xkcd.com", published = 1_793_595_600, url = "https://xkcd.com/3319/")
        assertEquals(Outcome.Ok, core.refresh())

        assertEquals("https://xkcd.com/3319/", core.originalPageUrl(core.articles.single()))
        assertEquals(emptyList(), followIt.requests)
    }

    @Test
    fun `if follow_it cannot be reached, its tracking link is used, which still leads there in a browser`() =
        runBlocking<Unit> {
            val article = addPostillon()
            followIt.close()

            assertEquals("https://api.follow.it$POSTILLON_STORY_PATH?utm_source=follow.it", core.originalPageUrl(article))
        }

    @Test
    fun `the View Mode is Feed Content by default, and a chosen one is kept across a restart`() = runBlocking<Unit> {
        val article = addPostillon()
        assertEquals(ViewMode.FeedContent, core.feedSettings(article.feedId).viewMode)

        core.setFeedSettings(article.feedId, FeedSettings(viewMode = ViewMode.OriginalPage))
        core.close() // the app is killed
        core = Core(storeDir = storeDir, secretBox = TestSecretBox(), httpClient = followIt.client())

        assertEquals(ViewMode.OriginalPage, core.feedSettings(article.feedId).viewMode)
    }

    private companion object {
        const val POSTILLON_STORY_PATH = "/track-rss-story-click/v3/hVR569XapuSu2Y10IQaUZBKylwdMW3JA"
        /** The `Location` follow.it answered for this story on 2026-11-06. */
        const val POSTILLON_REDIRECT = "https://api.follow.it/redirect-to-url?q=" +
            "https%3A%2F%2Fwww.der-postillon.com%2F2016%2F10%2F1.-klasse-schneller.html%3Futm_source%3Dfollow.it"
    }
}
