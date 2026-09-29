package com.calypsan.listenup.web.features.library

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.web.design.VirtualList
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Text

/**
 * A grid of book cards, rendering only the rows near the viewport — the library's grid, and every
 * other page that shows a shelf's worth of books (a genre, a tag, a mood).
 *
 * The windowing, keys and list semantics are [VirtualList]'s; this is what makes it a *book* grid:
 * the `.lib-grid` columns, the `.lib-card` every row is measured from, and the letter headers.
 * Row arithmetic depends on every card being exactly the same height — see the `.lib-title` /
 * `.lib-author` clamps in the stylesheet, and why [BookCard] renders its progress rail even when
 * it is empty.
 *
 * [letterOf] null for every book means no headers: a letter rail over a date sort would label runs
 * of books with letters that mean nothing.
 */
@Composable
@Suppress("LongParameterList")
internal fun VirtualBookGrid(
    books: List<BookListItem>,
    letterOf: (BookListItem) -> Char?,
    progressOf: (BookListItem) -> Float,
    onOpenBook: (String) -> Unit,
    heroBookId: String? = null,
    selecting: Boolean = false,
    isSelected: (String) -> Boolean = { false },
) {
    VirtualList(
        items = books,
        key = { it.id.value },
        containerClass = "lib-grid",
        itemSelector = ".lib-card",
        label = "Books",
        sectionOf = letterOf,
        headerSelector = ".lib-section",
        header = { letter -> Div(attrs = { classes("lib-section") }) { Text(letter.toString()) } },
    ) { book ->
        BookCard(
            book = book,
            progress = progressOf(book),
            onOpen = { onOpenBook(book.id.value) },
            isHero = book.id.value == heroBookId,
            selecting = selecting,
            isSelected = isSelected(book.id.value),
        )
    }
}
