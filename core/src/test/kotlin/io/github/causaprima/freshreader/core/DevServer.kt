package io.github.causaprima.freshreader.core

/**
 * Runs the [FakeFreshRss] as a standalone Server for checking the app on the emulator:
 * `./gradlew :core:runFakeServer`, `adb reverse tcp:8085 tcp:8085`, then log in with `http://127.0.0.1:8085`, user `reader`, API password `api-secret`.
 * `curl 'http://127.0.0.1:8085/dev/edit-tag-status?code=503'` makes marking read fail (`code=` without a value restores it);
 * `curl 'http://127.0.0.1:8085/dev/add-article?feed=Golem.de&title=Fresh'` adds an unread Article.
 */
fun main() {
    val server = FakeFreshRss()
    server.maxPageSize = 10
    listOf(
        "Golem.de" to "News", "ComputerBase" to "News", "xkcd" to "Pictures", "Dinosaur Comics" to "Pictures",
        "Der Postillon" to "Leisure", "FreshRSS releases" to "Updates", "Stellenmarkt" to "Job",
    ).forEach { (feed, category) ->
        server.addFeed(feed, category, icon = devFavicons[feed]?.let(::download), iconListed = feed != "Stellenmarkt")
    }
    val feeds = server.feeds.map { it.title }
    val start = System.currentTimeMillis() / 1000 - 3 * 24 * 3600
    repeat(25) { i ->
        server.addArticle(
            title = "Article ${i + 1}: " + listOf("A short title", "A considerably longer title that needs to wrap onto a second line to fit")[i % 2],
            feedTitle = feeds[i % feeds.size],
            published = start + i * 3 * 3600L,
            read = i % 6 == 5, // spread over the Feeds; with 7 Feeds, i % 7 would mark one Feed entirely read
            content = devContent(feeds[i % feeds.size]),
            url = devLink(feeds[i % feeds.size]),
        )
    }
    server.start(8085)
    println("Fake FreshRSS listening on ${server.url} (emulator, after adb reverse: http://127.0.0.1:8085)")
    Thread.currentThread().join()
}

/**
 * Real favicons, loaded when the dev server starts: an ICO, a PNG and an SVG (GitHub's, as FreshRSS would pick
 * for FreshRSS releases). Dinosaur Comics gets FreshRSS's default icon, Stellenmarkt no iconUrl at all.
 */
private val devFavicons = mapOf(
    "Golem.de" to "https://www.golem.de/favicon.ico",
    "ComputerBase" to "https://www.computerbase.de/favicon.ico",
    "xkcd" to "https://xkcd.com/s/919f27.ico",
    "Der Postillon" to "https://www.der-postillon.com/favicon.ico",
    "FreshRSS releases" to "https://github.githubassets.com/favicons/favicon.svg",
)

private fun download(url: String): ByteArray? = runCatching { java.net.URI(url).toURL().readBytes() }
    .onFailure { println("Could not load the favicon $url: $it") }
    .getOrNull()

/** Feed Content resembling what these Feeds deliver, to check the Article View's layout. */
private fun devContent(feed: String): String = when (feed) {
    // Captured from the real Feeds, as the Core tests use them.
    "xkcd" -> fixture("xkcd-3319.html")
    "Dinosaur Comics" -> fixture("qwantz-4538.html")
    "Der Postillon" -> fixture("postillon-1-klasse.html")
    else -> """
        <p>An opening paragraph long enough to wrap across several lines on a phone screen, so that line length,
        font size and margins of the Article View can be judged. <a href="https://freshrss.org/">A link to FreshRSS</a>.</p>
        <p><img src="data:image/svg+xml;base64,$WIDE_IMAGE" alt="A 1600 px wide image"></p>
        <p>A closing paragraph after the image.</p>
    """.trimIndent()
}

/**
 * Real links, so that the Original Page and the Reader View can be checked on the device: Der Postillon's follow.it
 * tracking link (captured 2026-11-06) for unwrapping and its consent wall, a Golem.de Article for a consent given once.
 */
private fun devLink(feed: String): String? = when (feed) {
    "Der Postillon" -> "https://api.follow.it/track-rss-story-click/v3/hVR569XapuSu2Y10IQaUZBKylwdMW3JA?utm_source=follow.it"
    "xkcd" -> "https://xkcd.com/3319/"
    // redirects to its consent page until consent is given
    "Golem.de" -> "https://www.golem.de/news/copernicus-2026-liegt-fast-sicher-ueber-der-1-5-grad-marke-2611-214939.html"
    else -> null
}

private val WIDE_IMAGE = java.util.Base64.getEncoder().encodeToString(
    """<svg xmlns="http://www.w3.org/2000/svg" width="1600" height="400"><rect width="1600" height="400" fill="#f57c00"/>
    <text x="800" y="220" font-size="64" text-anchor="middle" fill="white">1600 px wide</text></svg>""".toByteArray(),
)

