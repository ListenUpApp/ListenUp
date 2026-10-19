package com.calypsan.listenup.client.features.library.components

import com.calypsan.listenup.client.design.components.AlphabetIndex
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.presentation.library.LetterSection

/**
 * An item in the books grid: a section header or a book.
 */
internal sealed interface BookGridItem {
    /** The grid key, unique even when a letter recurs (a `#` run first and last). */
    val key: String

    /** Section divider showing the [letter] heading above the books that follow it; [ordinal] is its run's place. */
    data class Header(
        val letter: Char,
        val ordinal: Int,
    ) : BookGridItem {
        override val key: String get() = "header-$ordinal"
    }

    /** A single book in the grid. */
    data class BookItem(
        val book: BookListItem,
    ) : BookGridItem {
        override val key: String get() = book.id.value
    }
}

/** The books grid's rows: a header before each shared [LetterSection]; plain books when there are none. */
internal fun bookGridItems(
    books: List<BookListItem>,
    letters: List<LetterSection>,
): List<BookGridItem> {
    if (letters.isEmpty()) return books.map(BookGridItem::BookItem)
    val headerBefore =
        letters.withIndex().associate { (ordinal, section) ->
            section.firstIndex to BookGridItem.Header(section.letter.first(), ordinal)
        }
    return buildList {
        books.forEachIndexed { index, book ->
            headerBefore[index]?.let(::add)
            add(BookGridItem.BookItem(book))
        }
    }
}

/**
 * The scrollbar's letter → lazy-item index, from the shared sections. With headers, section k's header
 * sits at firstIndex + k (the k headers before it) + [leadingItems] (e.g. the inbox entry). A letter
 * that recurs jumps to its first run; the rail keeps list order, so a descending sort runs Z→A.
 */
internal fun libraryAlphabetIndex(
    letters: List<LetterSection>,
    withHeaders: Boolean,
    leadingItems: Int,
): AlphabetIndex? {
    if (letters.isEmpty()) return null
    val positions = LinkedHashMap<Char, Int>()
    letters.forEachIndexed { ordinal, section ->
        val letter = section.letter.first()
        if (letter !in positions) {
            positions[letter] = section.firstIndex + leadingItems + if (withHeaders) ordinal else 0
        }
    }
    return AlphabetIndex(letters = positions.keys.toList(), letterToIndex = positions)
}
