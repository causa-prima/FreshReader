package io.github.causaprima.freshreader.core

/**
 * Runs the [FakeFreshRss] as a standalone Server for checking the app on the emulator:
 * `./gradlew :core:runFakeServer`, `adb reverse tcp:8085 tcp:8085`, then log in with `http://127.0.0.1:8085`, user `reader`, API password `api-secret`.
 */
fun main() {
    val server = FakeFreshRss()
    server.maxPageSize = 10
    val feeds = listOf("Golem.de", "xkcd", "Der Postillon", "ComputerBase", "Dinosaur Comics")
    val start = System.currentTimeMillis() / 1000 - 3 * 24 * 3600
    repeat(25) { i ->
        server.addArticle(
            title = "Article ${i + 1}: " + listOf("A short title", "A considerably longer title that needs to wrap onto a second line to fit")[i % 2],
            feedTitle = feeds[i % feeds.size],
            published = start + i * 3 * 3600L,
            read = i % 7 == 3,
        )
    }
    server.start(8085)
    println("Fake FreshRSS listening on ${server.url} (emulator, after adb reverse: http://127.0.0.1:8085)")
    Thread.currentThread().join()
}
