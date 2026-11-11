package io.github.causaprima.freshreader.core

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.seconds

suspend fun Core.awaitSyncStatus(predicate: (SyncStatus) -> Boolean): SyncStatus =
    withTimeout(5.seconds) { syncStatus.first(predicate) }

/** Waits until all read marks have reached the Server. */
suspend fun Core.awaitSynced() = awaitSyncStatus { it == SyncStatus.Synced }

/** A clock the test moves forward. */
class MutableClock(var now: Instant) : Clock() {
    override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
}

/** Feed Content captured from a real Feed, in `src/test/resources/fixtures`. */
fun fixture(name: String): String =
    checkNotNull(MutableClock::class.java.getResource("/fixtures/$name")) { "no fixture $name" }.readText()

/** What the reader sees of [document] as text, whitespace collapsed; attributes such as `title` are not visible. */
fun visibleText(document: String): String = Jsoup.parse(document).body().text()

/** The visible text that comes after the first element matching [cssQuery], in reading order. */
fun textAfter(document: String, cssQuery: String): String {
    val body = Jsoup.parse(document).body()
    val start = checkNotNull(body.selectFirst(cssQuery)) { "no $cssQuery in the document" }
    val text = StringBuilder()
    var passed = false
    NodeTraversor.traverse(NodeVisitor { node, _ ->
        if (node === start) passed = true else if (passed && node is TextNode) text.append(node.text())
    }, body)
    return text.toString().replace(Regex("\\s+"), " ")
}
