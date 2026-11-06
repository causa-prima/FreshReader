package io.github.causaprima.freshreader.core

/**
 * Runs the [FakeFreshRss] as a standalone Server for checking the app on the emulator:
 * `./gradlew :core:runFakeServer`, `adb reverse tcp:8085 tcp:8085`, then log in with `http://127.0.0.1:8085`, user `reader`, API password `api-secret`.
 * `curl 'http://127.0.0.1:8085/dev/edit-tag-status?code=503'` makes marking read fail (`code=` without a value restores it).
 */
fun main() {
    val server = FakeFreshRss()
    server.maxPageSize = 10
    listOf(
        "Golem.de" to "News", "ComputerBase" to "News", "xkcd" to "Pictures", "Dinosaur Comics" to "Pictures",
        "Der Postillon" to "Leisure", "FreshRSS releases" to "Updates", "Stellenmarkt" to "Job",
    ).forEach { (feed, category) -> server.addFeed(feed, category) }
    val feeds = server.feeds.map { it.title }
    val start = System.currentTimeMillis() / 1000 - 3 * 24 * 3600
    repeat(25) { i ->
        server.addArticle(
            title = "Article ${i + 1}: " + listOf("A short title", "A considerably longer title that needs to wrap onto a second line to fit")[i % 2],
            feedTitle = feeds[i % feeds.size],
            published = start + i * 3 * 3600L,
            read = i % 6 == 5, // spread over the Feeds; with 7 Feeds, i % 7 would mark one Feed entirely read
            content = devContent(feeds[i % feeds.size]),
        )
    }
    server.start(8085)
    println("Fake FreshRSS listening on ${server.url} (emulator, after adb reverse: http://127.0.0.1:8085)")
    Thread.currentThread().join()
}

/** Feed Content resembling what these Feeds deliver, to check the Article View's layout. */
private fun devContent(feed: String): String = when (feed) {
    "xkcd" -> """<img src="https://imgs.xkcd.com/comics/elevators.png" title="Bonus Text hidden in the hover title." alt="Elevators">"""
    // Dinosaur Comics embeds its comic over plain http.
    "Dinosaur Comics" -> """<p><img src="http://www.qwantz.com/comics/comic2-5237.png" title="Hidden hover text"></p>"""
    else -> """
        <p>An opening paragraph long enough to wrap across several lines on a phone screen, so that line length,
        font size and margins of the Article View can be judged. <a href="https://freshrss.org/">A link to FreshRSS</a>.</p>
        <p><img src="data:image/svg+xml;base64,$WIDE_IMAGE" alt="A 1600 px wide image"></p>
        <p>A closing paragraph after the image.</p>
    """.trimIndent()
}

private val WIDE_IMAGE = java.util.Base64.getEncoder().encodeToString(
    """<svg xmlns="http://www.w3.org/2000/svg" width="1600" height="400"><rect width="1600" height="400" fill="#f57c00"/>
    <text x="800" y="220" font-size="64" text-anchor="middle" fill="white">1600 px wide</text></svg>""".toByteArray(),
)

