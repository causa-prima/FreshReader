package io.github.causaprima.freshreader.core

/** Category for Articles whose Feed is missing from the Server's Feed list. */
internal const val UNKNOWN_CATEGORY = "Uncategorized"

/**
 * Groups Articles into one section per Category of their Feed. Sections are ordered alphabetically;
 * within a section the Articles of all its Feeds are mixed, oldest first. Empty Categories never appear.
 */
internal fun buildSections(articles: List<Article>, categoryOfFeed: Map<String, String>): List<Section> =
    articles
        .groupBy { categoryOfFeed[it.feedId] ?: UNKNOWN_CATEGORY }
        .map { (category, inCategory) -> Section(category, inCategory.sortedBy { it.published }) }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.category })
