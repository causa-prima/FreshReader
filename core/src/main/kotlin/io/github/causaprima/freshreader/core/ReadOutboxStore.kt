package io.github.causaprima.freshreader.core

import kotlinx.serialization.json.Json
import java.io.File

/**
 * Keeps the read marks not yet confirmed by the Server on the device, so that they survive the app
 * being killed. Article ids are not secret, so they are stored as plain JSON.
 */
internal class ReadOutboxStore(dir: File) {
    private val file = File(dir, "read-outbox.json")

    fun load(): List<String> {
        if (!file.isFile) return emptyList()
        return runCatching { Json.decodeFromString<List<String>>(file.readText()) }.getOrDefault(emptyList())
    }

    fun save(articleIds: Collection<String>) =
        writeAtomically(file, Json.encodeToString(articleIds.toList()).encodeToByteArray())
}
