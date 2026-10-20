package com.calypsan.listenup.web.features.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.presentation.library.BookCardStatus
import com.calypsan.listenup.client.presentation.library.LetterSection
import com.calypsan.listenup.web.design.VirtualJump
import com.calypsan.listenup.web.design.VirtualList
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * A grid of book cards, rendering only the rows near the viewport — the library's grid, and every
 * other page that shows a shelf's worth of books (a genre, a tag, a mood).
 *
 * The windowing, keys and list semantics are [VirtualList]'s; this is what makes it a *book* grid:
 * the `.lib-grid` columns, the `.lib-card` every row is measured from, and the letter headers.
 * Row arithmetic depends on every card being exactly the same height — see the `.lib-title` /
 * `.lib-author` clamps in the stylesheet, and why [BookCard] renders its narrator line even when
 * the book has none.
 *
 * [letters] are the shared Library letter sections over [books] (`LibraryUiState.Loaded.bookLetters`),
 * so the headers file every book exactly where Android and iOS do. Empty means no headers: a letter
 * over a date sort would label runs of books with letters that mean nothing. Each header carries its
 * section's size ("96 books").
 *
 * [trailingPlaceholders] book-shaped blanks follow the last book while the library is still arriving: the grid says
 * more is coming rather than ending as if it were whole. Each is a `.lib-card`, exactly a card's height, so the row
 * arithmetic holds.
 */
@Composable
@Suppress("LongParameterList")
internal fun VirtualBookGrid(
    books: List<BookListItem>,
    statusOf: (BookListItem) -> BookCardStatus?,
    onOpenBook: (String) -> Unit,
    letters: List<LetterSection> = emptyList(),
    heroBookId: String? = null,
    selecting: Boolean = false,
    isSelected: (String) -> Boolean = { false },
    jump: VirtualJump? = null,
    trailingPlaceholders: Int = 0,
) {
    val entries =
        remember(books, trailingPlaceholders) {
            books.map { GridEntry.Book(it) } + List(trailingPlaceholders) { GridEntry.Placeholder(it) }
        }
    val letterById =
        remember(books, letters) {
            buildMap {
                letters.forEach { section ->
                    for (index in section.firstIndex until minOf(section.firstIndex + section.count, books.size)) {
                        put(books[index].id.value, section.letter.first())
                    }
                }
            }
        }
    // A letter that recurs (only `#` does, in practice) is captioned with its first run's size.
    val captionByLetter =
        remember(letters) {
            letters.groupBy { it.letter.first() }.mapValues { (_, runs) -> runs.first().count }
        }
    VirtualList(
        items = entries,
        key = { entry ->
            when (entry) {
                is GridEntry.Book -> entry.book.id.value
                is GridEntry.Placeholder -> "arriving-placeholder-${entry.index}"
            }
        },
        containerClass = "lib-grid",
        itemSelector = ".lib-card",
        label = "Books",
        sectionOf = { entry -> (entry as? GridEntry.Book)?.let { letterById[it.book.id.value] } },
        headerSelector = ".lib-section",
        header = { letter ->
            Div(attrs = { classes("lib-section") }) {
                Span { Text(letter.toString()) }
                Span(
                    attrs = { classes("lib-section-c") },
                ) { Text(captionByLetter[letter]?.let(::sectionCaption).orEmpty()) }
            }
        },
        jump = jump,
    ) { entry ->
        when (entry) {
            is GridEntry.Book -> {
                val book = entry.book
                BookCard(
                    book = book,
                    status = statusOf(book),
                    onOpen = { onOpenBook(book.id.value) },
                    isHero = book.id.value == heroBookId,
                    selecting = selecting,
                    isSelected = isSelected(book.id.value),
                )
            }

            is GridEntry.Placeholder -> {
                ArrivingPlaceholderCard(isNamed = entry.index == 0)
            }
        }
    }
}

/** One cell of the book grid: a book, or a blank where more books are arriving. */
private sealed interface GridEntry {
    data class Book(
        val book: BookListItem,
    ) : GridEntry

    data class Placeholder(
        val index: Int,
    ) : GridEntry
}

/**
 * A book card with nothing in it yet: the same lines, so the same height. The first is named for a screen reader
 * ("More books arriving"); the rest are hidden, so the news is heard once rather than four times.
 */
@Composable
private fun ArrivingPlaceholderCard(isNamed: Boolean) {
    Div(attrs = {
        classes("lib-card", "is-placeholder")
        if (isNamed) {
            attr("role", "img")
            attr("aria-label", "More books arriving")
        } else {
            attr("aria-hidden", "true")
        }
    }) {
        Div(attrs = { classes("lib-cover-ph", "skel") })
        Div(attrs = { classes("lib-title") }) { Span(attrs = { classes("lib-ph-line", "skel") }) { Text(NBSP) } }
        Div(attrs = { classes("lib-author") }) { Span(attrs = { classes("lib-ph-line", "is-short", "skel") }) { Text(NBSP) } }
        Div(attrs = { classes("lib-narrator") }) { Text(NBSP) }
        Div(attrs = { classes("lib-meta") }) { Text(NBSP) }
    }
}

private const val NBSP = "\u00A0"


/**
 * The Library's Books grid with its A–Z jump rail beside it (spec §5.2): the rail's letters are the
 * shared [letters], and pressing one brings that section to the top and focuses its first book.
 */
@Composable
@Suppress("LongParameterList")
internal fun BooksWithRail(
    books: List<BookListItem>,
    letters: List<LetterSection>,
    statusOf: (BookListItem) -> BookCardStatus?,
    onOpenBook: (String) -> Unit,
    heroBookId: String? = null,
    selecting: Boolean = false,
    isSelected: (String) -> Boolean = { false },
    trailingPlaceholders: Int = 0,
) {
    WithJumpRail(letters) { jump ->
        VirtualBookGrid(
            books = books,
            letters = letters,
            statusOf = statusOf,
            onOpenBook = onOpenBook,
            heroBookId = heroBookId,
            selecting = selecting,
            isSelected = isSelected,
            jump = jump,
            trailingPlaceholders = trailingPlaceholders,
        )
    }
}

/** "1 book", "96 books", "1,286 books". */
private fun sectionCaption(count: Int): String = if (count == 1) "1 book" else "${groupedCount(count)} books"
