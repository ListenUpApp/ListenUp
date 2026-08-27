package com.calypsan.listenup.web.features.shelf

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.domain.model.ShelfBook
import com.calypsan.listenup.client.domain.model.ShelfDetail
import com.calypsan.listenup.client.presentation.shelf.ShelfDetailUiState
import com.calypsan.listenup.web.design.Cover
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.design.coverUrl
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

private const val EMPTY_CLASS = "empty"

private const val SHELF_COVER_WIDTH = 120

private const val SHELF_COVER_HEIGHT = 180

private const val DRAG_ICON_SIZE = 16

/**
 * One shelf: what is on it, and — if it is yours — the two ways to change that.
 *
 * Ownership is the server's answer ([ShelfDetail.isOwner]), not a guess from the current user id.
 * A shelf shared with you renders identically minus the controls, which is why the read path has no
 * owner branches in it at all.
 */
@Composable
fun ShelfDetailPage(
    state: ShelfDetailUiState,
    onOpenBook: (String) -> Unit,
    onRemoveBook: (String) -> Unit,
    onReorder: (List<String>) -> Unit,
    onEditShelf: (String) -> Unit,
    onOpenLibrary: () -> Unit,
) {
    Div(attrs = { classes("shelf") }) {
        when (state) {
            is ShelfDetailUiState.Idle, is ShelfDetailUiState.Loading -> {
                Div(attrs = { classes("skel", "shelf-skel") })
            }

            is ShelfDetailUiState.Error -> {
                Div(attrs = { classes(EMPTY_CLASS) }) {
                    H3 { Text("This shelf could not be opened") }
                    P { Text(state.message) }
                    Button(attrs = {
                        classes("btn")
                        attr(ATTR_TYPE, VALUE_BUTTON)
                        onClick { onOpenLibrary() }
                    }) { Text("Back to library") }
                }
            }

            is ShelfDetailUiState.Ready -> {
                ShelfHeader(state.detail, onEditShelf)
                if (state.detail.books.isEmpty()) {
                    Div(attrs = { classes(EMPTY_CLASS) }) {
                        H3 { Text("This shelf is empty") }
                        P { Text("Add books to it from any book's page.") }
                    }
                } else {
                    ShelfBooks(
                        books = state.detail.books,
                        isOwner = state.detail.isOwner,
                        onOpenBook = onOpenBook,
                        onRemoveBook = onRemoveBook,
                        onReorder = onReorder,
                    )
                }
            }
        }
    }
}

@Composable
private fun ShelfHeader(
    detail: ShelfDetail,
    onEditShelf: (String) -> Unit,
) {
    Div(attrs = { classes("shelf-head") }) {
        Div(attrs = { classes("shelf-head-text") }) {
            H1(attrs = { classes("shelf-title") }) { Text(detail.name) }
            detail.description?.takeIf { it.isNotBlank() }?.let { description ->
                P(attrs = { classes("shelf-desc") }) { Text(description) }
            }
            Div(attrs = { classes("shelf-meta") }) {
                Span { Text(bookCountLabel(detail.bookCount)) }
                if (detail.totalDurationSeconds > 0) {
                    Span { Text(detail.formattedDuration) }
                }
                // Only worth saying when it is true: every shelf that is not private is shared, and
                // labelling the common case adds a word to every shelf to inform nobody.
                if (detail.isPrivate) {
                    Span(attrs = { classes("shelf-private") }) {
                        Icon(WebIcon.Lock, size = DRAG_ICON_SIZE)
                        Text("Private")
                    }
                }
            }
        }
        if (detail.isOwner) {
            Button(attrs = {
                classes("btn")
                attr(ATTR_TYPE, VALUE_BUTTON)
                onClick { onEditShelf(detail.idString) }
            }) { Text("Edit shelf") }
        }
    }
}

/**
 * The books, in the order the owner put them.
 *
 * ## Dragging
 *
 * The dragged row's index is held in composition rather than read back out of the DOM, because the
 * only thing a `drop` event reliably carries is where it landed — reconstructing where it came from
 * from the DOM means trusting that nothing re-rendered mid-drag, which a live shelf does not
 * promise.
 *
 * `dragover` must call `preventDefault()` or the browser refuses the drop: the default action for a
 * dragged-over element is "not a drop target", and there is no other way to opt in.
 *
 * The handle is also a real focusable control that moves the row on Arrow keys. Dragging is the
 * gesture this screen is built around, but a reorder that only a mouse can perform is a feature
 * keyboard users simply do not have — and the handle is already the right place to put the
 * alternative, at no cost in visible UI.
 */
@Composable
private fun ShelfBooks(
    books: List<ShelfBook>,
    isOwner: Boolean,
    onOpenBook: (String) -> Unit,
    onRemoveBook: (String) -> Unit,
    onReorder: (List<String>) -> Unit,
) {
    var draggingIndex by remember(books) { mutableStateOf<Int?>(null) }

    fun move(
        from: Int,
        to: Int,
    ) {
        val reordered = reorderedBy(books, from, to)
        if (reordered !== books) {
            onReorder(reordered.map { it.idString })
        }
    }

    Div(attrs = { classes("shelf-books") }) {
        books.forEachIndexed { index, book ->
            Div(attrs = {
                classes("shelf-book")
                if (isOwner) {
                    attr("draggable", "true")
                    onDragStart { draggingIndex = index }
                    onDragEnd { draggingIndex = null }
                    onDragOver { event ->
                        // Without this the browser treats the row as "not a drop target" and the
                        // drop never fires. There is no declarative way to say otherwise.
                        event.preventDefault()
                    }
                    onDrop { event ->
                        event.preventDefault()
                        draggingIndex?.let { from -> move(from, index) }
                        draggingIndex = null
                    }
                }
            }) {
                if (isOwner) {
                    DragHandle(
                        label = "Reorder ${book.title}",
                        onMoveUp = { move(index, index - 1) },
                        onMoveDown = { move(index, index + 1) },
                    )
                }

                Button(attrs = {
                    classes("shelf-book-open")
                    attr(ATTR_TYPE, VALUE_BUTTON)
                    onClick { onOpenBook(book.idString) }
                }) {
                    Cover(
                        title = book.title,
                        imageUrl = coverUrl(book.idString, book.coverHash, width = SHELF_COVER_WIDTH),
                        size = SHELF_COVER_WIDTH,
                        height = SHELF_COVER_HEIGHT,
                    )
                    Span(attrs = { classes("shelf-book-t") }) { Text(book.title) }
                    book.authorNames.takeIf { it.isNotEmpty() }?.let { authors ->
                        Span(attrs = { classes("shelf-book-sub") }) { Text(authors.joinToString(", ")) }
                    }
                }

                if (isOwner) {
                    Button(attrs = {
                        classes("shelf-book-x")
                        attr(ATTR_TYPE, VALUE_BUTTON)
                        attr("aria-label", "Remove ${book.title} from this shelf")
                        attr("title", "Remove from shelf")
                        onClick { onRemoveBook(book.idString) }
                    }) { Icon(WebIcon.Trash, size = DRAG_ICON_SIZE) }
                }
            }
        }
    }
}

/**
 * The grip: a drag affordance for a pointer, and an arrow-key control for everyone else.
 *
 * `aria-label` names the book because "Reorder" alone, repeated down a shelf, tells a screen-reader
 * user which control they are on but not which row it belongs to.
 */
@Composable
private fun DragHandle(
    label: String,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    Button(attrs = {
        classes("shelf-grip")
        attr(ATTR_TYPE, VALUE_BUTTON)
        attr("aria-label", label)
        attr("title", "Drag to reorder, or use the arrow keys")
        onKeyDown { event ->
            when (event.key) {
                "ArrowUp" -> {
                    // Otherwise the page scrolls under the listener while the row moves.
                    event.preventDefault()
                    onMoveUp()
                }

                "ArrowDown" -> {
                    event.preventDefault()
                    onMoveDown()
                }

                else -> {
                    Unit
                }
            }
        }
    }) {
        Icon(WebIcon.Grip, size = DRAG_ICON_SIZE)
    }
}

/** `"1 book"` / `"12 books"` — the count, agreeing with its noun. */
internal fun bookCountLabel(count: Int): String = "$count book${if (count == 1) "" else "s"}"

private const val ATTR_TYPE = "type"

private const val VALUE_BUTTON = "button"
