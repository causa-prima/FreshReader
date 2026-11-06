package io.github.causaprima.freshreader.core

/** Category for Articles whose Feed is missing from the Server's Feed list. */
internal const val UNKNOWN_CATEGORY = "Uncategorized"

/**
 * Groups Articles into one section per Category of their Feed. Sections follow the Reading Order;
 * Categories not in it come afterwards, alphabetically. Within a section the Articles of all its Feeds
 * are mixed, oldest first. Empty Categories never appear.
 */
internal fun buildSections(
    articles: List<Article>,
    categoryOfFeed: Map<String, String>,
    readingOrder: List<String>,
): List<Section> {
    val byCategory = articles.groupBy { categoryOfFeed[it.feedId] ?: UNKNOWN_CATEGORY }
    return inReadingOrder(byCategory.keys, readingOrder)
        .map { category -> Section(category, byCategory.getValue(category).sortedBy { it.published }) }
}

/** Orders [categories] by the Reading Order; Categories not in it come afterwards, alphabetically. */
internal fun inReadingOrder(categories: Collection<String>, readingOrder: List<String>): List<String> {
    val position = readingOrder.withIndex().associate { (index, category) -> category to index }
    return categories.distinct().sortedWith(
        compareBy<String> { position[it] ?: Int.MAX_VALUE }.thenBy(String.CASE_INSENSITIVE_ORDER) { it },
    )
}
