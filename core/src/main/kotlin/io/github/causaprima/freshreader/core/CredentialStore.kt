package io.github.causaprima.freshreader.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
internal data class Credentials(val serverUrl: String, val username: String, val apiPassword: String)

/** Keeps the reader's Credentials on the device, encrypted with a [SecretBox]. */
internal class CredentialStore(dir: File, private val secretBox: SecretBox) {
    private val file = File(dir, "credentials.bin")

    fun load(): Credentials? {
        if (!file.isFile) return null
        return runCatching {
            Json.decodeFromString<Credentials>(secretBox.open(file.readBytes()).decodeToString())
        }.getOrNull()
    }

    fun save(credentials: Credentials) {
        file.parentFile.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeBytes(secretBox.seal(Json.encodeToString(credentials).encodeToByteArray()))
        if (!tmp.renameTo(file)) error("Could not write $file")
    }
}
