package com.calypsan.listenup.client.presentation.library

import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.util.nameLetter
import com.calypsan.listenup.client.util.sortLetter

/**
 * The one derivation every platform's letter headers and jump index read: [items] cut into runs that
 * share [letterOf]'s letter, in list order.
 */
internal fun <T> letterSections(
    items: List<T>,
    letterOf: (T) -> Char,
): List<LetterSection> {
    val sections = mutableListOf<LetterSection>()
    var runLetter: Char? = null
    var runStart = 0
    items.forEachIndexed { index, item ->
        val letter = letterOf(item)
        if (letter != runLetter) {
            runLetter?.let { sections += LetterSection(it.toString(), runStart, index - runStart) }
            runLetter = letter
            runStart = index
        }
    }
    runLetter?.let { sections += LetterSection(it.toString(), runStart, items.size - runStart) }
    return sections
}

/**
 * The letter a book files under for [category], matching the key the Library's book sort ordered by;
 * null for a sort with no index (length, year, date added, ratings).
 */
internal fun bookLetterOf(
    category: SortCategory,
    ignoreArticles: Boolean,
): ((BookListItem) -> Char)? =
    when (category) {
        SortCategory.TITLE -> { book -> book.title.sortLetter(ignoreArticles) }

        SortCategory.AUTHOR -> { book -> book.authorNames.nameLetter() }

        SortCategory.SERIES -> { book -> book.seriesName.nameLetter() }

        SortCategory.NAME,
        SortCategory.DURATION,
        SortCategory.YEAR,
        SortCategory.BOOK_COUNT,
        SortCategory.ADDED,
        SortCategory.RATING,
        SortCategory.LISTENER_RATING,
        -> null
    }

/** Series by name are sorted article-aware, so they file the same way; other series sorts have no index. */
internal fun seriesLetterOf(
    category: SortCategory,
    ignoreArticles: Boolean,
): ((String) -> Char)? = if (category == SortCategory.NAME) { name -> name.sortLetter(ignoreArticles) } else null

/** Authors and narrators by name: a person's name never loses its article ("The Rolling Stones" is T). */
internal fun contributorLetterOf(category: SortCategory): ((String) -> Char)? =
    if (category == SortCategory.NAME) { name -> name.nameLetter() } else null
