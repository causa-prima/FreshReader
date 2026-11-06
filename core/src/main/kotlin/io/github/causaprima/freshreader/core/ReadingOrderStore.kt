package io.github.causaprima.freshreader.core

import kotlinx.serialization.json.Json
import java.io.File

/** Keeps the Reading Order on the device, by Category name. It is not secret, so it is stored as plain JSON. */
internal class ReadingOrderStore(dir: File) {
    private val file = File(dir, "reading-order.json")

    fun load(): List<String> {
        if (!file.isFile) return emptyList()
        return runCatching { Json.decodeFromString<List<String>>(file.readText()) }.getOrDefault(emptyList())
    }

    fun save(order: List<String>) = writeAtomically(file, Json.encodeToString(order).encodeToByteArray())
}

/** Replaces [file] with [bytes] so that a crash leaves either the old or the new content, never a mix. */
internal fun writeAtomically(file: File, bytes: ByteArray) {
    file.parentFile.mkdirs()
    val tmp = File(file.parentFile, "${file.name}.tmp")
    tmp.writeBytes(bytes)
    if (!tmp.renameTo(file)) error("Could not write $file")
}
