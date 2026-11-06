package io.github.causaprima.freshreader.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** The reader's preferences for one Feed, kept on the device. */
@Serializable
data class FeedSettings(
    /** Show text the Feed hides in image titles and mail subjects visibly in the Article View. */
    val bonusText: Boolean = false,
)

/** Keeps the Feed Settings on the device, by Feed id, which stays when a Feed is renamed. Not secret: plain JSON. */
internal class FeedSettingsStore(dir: File) {
    private val file = File(dir, "feed-settings.json")
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): Map<String, FeedSettings> {
        if (!file.isFile) return emptyMap()
        return runCatching { json.decodeFromString<Map<String, FeedSettings>>(file.readText()) }.getOrDefault(emptyMap())
    }

    fun save(settings: Map<String, FeedSettings>) = writeAtomically(file, json.encodeToString(settings).encodeToByteArray())
}
