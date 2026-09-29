package com.calypsan.listenup.web.features.shelf

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import com.calypsan.listenup.client.domain.model.ShelfBook
import com.calypsan.listenup.client.domain.model.ShelfDetail
import com.calypsan.listenup.client.presentation.shelf.ShelfDetailUiState
import com.calypsan.listenup.web.design.EmptyState
import com.calypsan.listenup.web.design.Cover
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.PageHeader
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.design.coverUrl
import org.jetbrains.compose.web.attributes.AttrsScope
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLElement

private const val SHELF_COVER_WIDTH = 120

private const val DRAG_ICON_SIZE = 16

/**
 * One shelf: what is on it, and — if it is yours — the two ways to change that.
 *
 * Ownership is the server's answer ([ShelfDetail.isOwner]), not a guess from the current user id.
 * A shelf shared with you renders identically minus the controls, which is why the read path has no
 * owner branches in it at all.
 *
 * [notice] is how a refused mutation reaches the reader. `ShelfDetailViewModel` has always pushed
 * these into a channel; nothing on web read it, so a reorder the server rejected reverted the list
 * on reload and said nothing at all about why.
 */
@Composable
fun ShelfDetailPage(
    state: ShelfDetailUiState,
    notice: String?,
    onDismissNotice: () -> Unit,
    onOpenBook: (String) -> Unit,
    onRemoveBook: (String) -> Unit,
    onReorder: (List<String>) -> Unit,
    onEditShelf: (String) -> Unit,
    onOpenLibrary: () -> Unit,
) {
    Div(attrs = { classes("shelf") }) {
        ShelfNotice(notice, onDismissNotice)

        when (state) {
            is ShelfDetailUiState.Idle, is ShelfDetailUiState.Loading -> {
                PageHeader(title = SHELF, pending = true)
                Div(attrs = { classes("skel", "shelf-skel") })
            }

            is ShelfDetailUiState.Error -> {
                PageHeader(title = SHELF)
                EmptyState(title = "This shelf could not be opened", body = state.message) {
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
                    EmptyState(title = "This shelf is empty", body = "Add books to it from any book's page.")
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
    PageHeader(
        title = detail.name,
        subtitle = detail.description?.takeIf { it.isNotBlank() },
        details = {
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
        },
        actions =
            if (detail.isOwner) {
                {
                    Button(attrs = {
                        classes("btn")
                        attr(ATTR_TYPE, VALUE_BUTTON)
                        onClick { onEditShelf(detail.idString) }
                    }) { Text("Edit shelf") }
                }
            } else {
                null
            },
    )
}

/**
 * The books, in the order the owner put them.
 *
 * ## Reordering, four ways
 *
 * - **Drag a row** with a mouse (HTML5 drag-and-drop, [shelfRowDrag]).
 * - **Drag the grip** with a finger or a pen ([touchDragOn]): a touchscreen does not reliably start
 *   an HTML5 drag, so pointer events carry it instead.
 * - **Move earlier / Move later** ([MoveButtons]): visible on every row, for anyone who cannot or
 *   would rather not drag — the same two moves Android offers as accessibility actions.
 * - **Arrow keys** on the focused grip.
 *
 * Every one of them goes through [ShelfReorder.move], which says where the book landed in a live
 * region and keeps focus on the control that moved it. Rows are keyed by book, so a moved row's
 * elements move with it.
 */
@Composable
private fun ShelfBooks(
    books: List<ShelfBook>,
    isOwner: Boolean,
    onOpenBook: (String) -> Unit,
    onRemoveBook: (String) -> Unit,
    onReorder: (List<String>) -> Unit,
) {
    val reorder = rememberShelfReorder(books, onReorder)

    ShelfAnnouncer(reorder.announcement)
    Div(attrs = { classes("shelf-books") }) {
        books.forEachIndexed { index, book ->
            key(book.idString) {
                ShelfRow(book, index, books.lastIndex, if (isOwner) reorder else null, onOpenBook, onRemoveBook)
            }
        }
    }
}

/** One book on the shelf. [reorder] is null for a visitor, who gets the book and no controls. */
@Composable
private fun ShelfRow(
    book: ShelfBook,
    index: Int,
    lastIndex: Int,
    reorder: ShelfReorder?,
    onOpenBook: (String) -> Unit,
    onRemoveBook: (String) -> Unit,
) {
    val register = reorder?.registrar(book.idString)
    Div(attrs = {
        classes("shelf-book")
        reorder?.let { shelfRowDrag(index, it) }
    }) {
        if (reorder != null && register != null) {
            DragHandle(
                label = "Reorder ${book.title}",
                register = register,
                onMoveUp = { reorder.move(index, index - 1, focusAfter = ShelfControl.Grip) },
                onMoveDown = { reorder.move(index, index + 1, focusAfter = ShelfControl.Grip) },
                touch = { touchDragOn(index, reorder) },
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
                decorative = true,
            )
            // One text block beside the cover, title over author. As two loose flex items they sat
            // side by side and neither could wrap, which pushed a phone row past the screen's edge.
            Span(attrs = { classes("shelf-book-text") }) {
                Span(attrs = { classes("shelf-book-t") }) { Text(book.title) }
                book.authorNames.takeIf { it.isNotEmpty() }?.let { authors ->
                    Span(attrs = { classes("shelf-book-sub") }) { Text(authors.joinToString(", ")) }
                }
            }
        }

        if (reorder != null && register != null) {
            Div(attrs = { classes("shelf-book-acts") }) {
                MoveButtons(
                    title = book.title,
                    index = index,
                    lastIndex = lastIndex,
                    onMove = { to ->
                        val control = if (to < index) ShelfControl.Earlier else ShelfControl.Later
                        reorder.move(index, to, focusAfter = control)
                    },
                    register = register,
                )
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

/**
 * The grip: a drag affordance for a mouse (the row's own HTML5 drag) and a finger ([touch]), and an
 * arrow-key control for everyone else.
 *
 * `aria-label` names the book because "Reorder" alone, repeated down a shelf, tells a screen-reader
 * user which control they are on but not which row it belongs to.
 */
@Composable
private fun DragHandle(
    label: String,
    register: (ShelfControl, HTMLElement?) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    touch: AttrsScope<out HTMLElement>.() -> Unit,
) {
    Button(attrs = {
        classes("shelf-grip")
        attr(ATTR_TYPE, VALUE_BUTTON)
        attr("aria-label", label)
        attr("title", "Drag to reorder, or use the arrow keys")
        registerAs(ShelfControl.Grip, register)
        touch()
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

/**
 * What went wrong with the last thing you asked for.
 *
 * A reorder or a removal that the server refuses is otherwise invisible: the shelf simply reloads
 * into the order it already had, which reads as the gesture not registering rather than as a
 * refusal. Dismissable, because the shelf underneath is still perfectly usable.
 */
@Composable
private fun ShelfNotice(
    message: String?,
    onDismiss: () -> Unit,
) {
    if (message == null) return

    Div(attrs = { classes("shelf-notice") }) {
        Span(attrs = { classes("shelf-notice-t") }) { Text(message) }
        Button(attrs = {
            classes("shelf-notice-x")
            attr(ATTR_TYPE, VALUE_BUTTON)
            attr("aria-label", "Dismiss")
            attr("title", "Dismiss")
            onClick { onDismiss() }
        }) { Icon(WebIcon.Check, size = DRAG_ICON_SIZE) }
    }
}

private const val SHELF = "Shelf"
