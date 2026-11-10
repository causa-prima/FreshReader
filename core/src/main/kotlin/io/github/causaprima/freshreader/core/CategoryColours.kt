package io.github.causaprima.freshreader.core

import kotlinx.serialization.json.Json
import java.io.File

/**
 * The fixed, muted palette a Category Colour comes from. Ten hues stay clearly apart even muted; the order is
 * the one automatic colours are handed out in, so that consecutive ones differ strongly.
 */
enum class CategoryColour { Blue, Orange, Green, Purple, Red, Teal, Yellow, Pink, Indigo, Lime }

/**
 * The Category Colour of each of [categories], in display order: the reader's [chosen] one, else the one at the
 * Category's position in the palette, or the next one after it that differs from both neighbours' colours. A
 * choice thus changes no automatic colour but its neighbours'.
 */
internal fun categoryColours(
    categories: List<String>,
    chosen: Map<String, CategoryColour>,
): Map<String, CategoryColour> {
    val palette = CategoryColour.entries
    val colours = mutableMapOf<String, CategoryColour>()
    categories.forEachIndexed { index, category ->
        colours[category] = chosen[category] ?: run {
            val neighbours = setOfNotNull(
                categories.getOrNull(index - 1)?.let(colours::get),
                categories.getOrNull(index + 1)?.let(chosen::get),
            )
            generateSequence(index) { it + 1 }.map { palette[it % palette.size] }.first { it !in neighbours }
        }
    }
    return colours
}

/** Keeps the Category Colours the reader chose on the device, by Category name. Not secret: plain JSON. */
internal class CategoryColourStore(dir: File) {
    private val file = File(dir, "category-colours.json")

    fun load(): Map<String, CategoryColour> {
        if (!file.isFile) return emptyMap()
        return runCatching { Json.decodeFromString<Map<String, CategoryColour>>(file.readText()) }.getOrDefault(emptyMap())
    }

    fun save(colours: Map<String, CategoryColour>) = writeAtomically(file, Json.encodeToString(colours).encodeToByteArray())
}
