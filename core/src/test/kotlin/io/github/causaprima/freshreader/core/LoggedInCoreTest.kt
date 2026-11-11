package io.github.causaprima.freshreader.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals

/**
 * The common starting point: a fake Server and a Core logged in to it, as after the reader's first login. The Core
 * keeps what it stores in [storeDir], so [restart] gets the app as it comes back after being killed.
 */
abstract class LoggedInCoreTest {
    protected val server = FakeFreshRss().start()

    @TempDir
    lateinit var storeDir: File

    protected val secretBox = TestSecretBox()
    protected lateinit var core: Core

    /** A Core over this test's store, as the app creates one on each start. */
    protected open fun newCore(): Core = Core(storeDir = storeDir, secretBox = secretBox)

    @BeforeEach
    fun logIn() = runBlocking<Unit> {
        core = newCore()
        assertEquals(Outcome.Ok, core.logIn(server.url, "reader", "api-secret"))
    }

    /** The app is killed and started again. */
    protected fun restart() {
        core.close()
        core = newCore()
    }

    @AfterEach
    fun closeCoreAndServer() {
        core.close()
        server.close()
    }
}
