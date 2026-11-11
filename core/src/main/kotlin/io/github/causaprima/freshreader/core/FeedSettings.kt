package io.github.causaprima.freshreader.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** The reader's preferences for one Feed, kept on the device. */
@Serializable
data class FeedSettings(
    /** How the Article View shows this Feed's Articles. */
    val viewMode: ViewMode = ViewMode.FeedContent,
    /** Show text the Feed hides in image titles and mail subjects visibly in the Article View. */
    val bonusText: Boolean = false,
) {
    /** Whether [bonusText] applies: to Feed Content and the Reader View; the Original Page shows the site as it is. */
    val bonusTextApplies: Boolean get() = viewMode != ViewMode.OriginalPage
}

/** How an Article is shown in the Article View. */
@Serializable
enum class ViewMode {
    /** The Article content exactly as the Server delivers it, prepared by the Core. */
    FeedContent,

    /** The Article's web page reduced to its main content, laid out like Feed Content. */
    ReaderView,

    /** The Article's web page on its own site, as-is, in the in-app browser. */
    OriginalPage,
}

/** Keeps the Feed Settings on the device, by Feed id, which stays when a Feed is renamed. Not secret: plain JSON. */
internal class FeedSettingsStore(dir: File) {
    private val file = File(dir, "feed-settings.json")
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true // a View Mode this version does not know falls back to the default
    }

    fun load(): Map<String, FeedSettings> {
        if (!file.isFile) return emptyMap()
        return runCatching { json.decodeFromString<Map<String, FeedSettings>>(file.readText()) }.getOrDefault(emptyMap())
    }

    fun save(settings: Map<String, FeedSettings>) = writeAtomically(file, json.encodeToString(settings).encodeToByteArray())
}
