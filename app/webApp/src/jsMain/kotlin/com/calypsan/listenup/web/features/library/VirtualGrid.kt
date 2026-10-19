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
) {
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
        items = books,
        key = { it.id.value },
        containerClass = "lib-grid",
        itemSelector = ".lib-card",
        label = "Books",
        sectionOf = { letterById[it.id.value] },
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
    ) { book ->
        BookCard(
            book = book,
            status = statusOf(book),
            onOpen = { onOpenBook(book.id.value) },
            isHero = book.id.value == heroBookId,
            selecting = selecting,
            isSelected = isSelected(book.id.value),
        )
    }
}

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
) {
    val jump = remember { VirtualJump() }
    val rail = remember(letters) { railLetters(letters) }
    Div(attrs = {
        classes("lib-books")
        if (rail.size > 1) classes("has-rail")
    }) {
        VirtualBookGrid(
            books = books,
            letters = letters,
            statusOf = statusOf,
            onOpenBook = onOpenBook,
            heroBookId = heroBookId,
            selecting = selecting,
            isSelected = isSelected,
            jump = jump,
        )
        JumpRail(rail) { jump.toSection(it.first()) }
    }
}

/** "1 book", "96 books", "1,286 books". */
private fun sectionCaption(count: Int): String = if (count == 1) "1 book" else "${groupedCount(count)} books"
