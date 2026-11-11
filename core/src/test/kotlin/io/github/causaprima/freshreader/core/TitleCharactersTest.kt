package io.github.causaprima.freshreader.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals

/**
 * FreshRSS's Google Reader API hands out `&`, `<` and `>` in titles and Feed names as their fullwidth forms
 * (＆, ＜, ＞), in Feed names also `,`, `/`, `?`, `\` and `;`. Titles a Feed escaped itself, as Der Postillon
 * does, arrive with their entities.
 */
class TitleCharactersTest {
    private val server = FakeFreshRss().start()

    @TempDir
    lateinit var storeDir: File

    private lateinit var core: Core

    @BeforeEach
    fun logIn() = runBlocking<Unit> {
        core = Core(storeDir = storeDir, secretBox = TestSecretBox())
        assertEquals(Outcome.Ok, core.logIn(server.url, "reader", "api-secret"))
    }

    @AfterEach
    fun tearDown() {
        core.close()
        server.close()
    }

    @Test
    fun `Article titles reach the list with the characters their entities and fullwidth stand-ins stand for`() =
        runBlocking<Unit> {
            server.addArticle("&quot;Ich bin 71&quot; – Merz", feedTitle = "Der Postillon", published = 1_800_000_000)
            server.addArticle("＆quot;Weniger＆quot; ist mehr", feedTitle = "Der Postillon", published = 1_800_000_100)
            server.addArticle("Fish ＆ Chips ＜3", feedTitle = "Der Postillon", published = 1_800_000_200)
            server.addArticle("&#039;Ja&#039; &amp; &lt;b&gt;", feedTitle = "Der Postillon", published = 1_800_000_300)

            assertEquals(Outcome.Ok, core.refresh())

            assertEquals(
                listOf("\"Ich bin 71\" – Merz", "\"Weniger\" ist mehr", "Fish & Chips <3", "'Ja' & <b>"),
                core.articles.map { it.title },
            )
        }

    @Test
    fun `Feed names reach the Articles with the characters their fullwidth stand-ins stand for`() = runBlocking<Unit> {
        server.addArticle("News", feedTitle = "Fish ＆ Chips， Bits／Bytes？ ＜Yes＞; Tom＆#039;s", published = 1_800_000_000)

        assertEquals(Outcome.Ok, core.refresh())

        assertEquals("Fish & Chips, Bits/Bytes? <Yes>; Tom's", core.articles.single().feedTitle)
    }
}
