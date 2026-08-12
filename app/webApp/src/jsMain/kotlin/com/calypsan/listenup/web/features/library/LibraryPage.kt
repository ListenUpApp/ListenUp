package com.calypsan.listenup.web.features.library

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.presentation.library.LibraryUiEvent
import com.calypsan.listenup.client.presentation.library.LibraryUiState
import com.calypsan.listenup.client.presentation.library.SortCategory
import com.calypsan.listenup.client.presentation.library.SortDirection
import org.jetbrains.compose.web.attributes.alt
import org.jetbrains.compose.web.css.percent
import org.jetbrains.compose.web.css.width
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.Img
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Text

/**
 * Categories the Books tab sorts by.
 *
 * `SortCategory.entries` also carries categories that only make sense for the Series, Authors and
 * Narrators tabs, so the row is an explicit list rather than the whole enum — a filter by name would
 * silently pick up whatever a future entry happens to be called.
 */
private val BOOK_SORT_CATEGORIES =
    listOf(SortCategory.TITLE, SortCategory.AUTHOR, SortCategory.ADDED, SortCategory.DURATION)

/**
 * The Books tab of the library.
 *
 * Renders [LibraryUiState] and nothing else — the sort, the progress and the "is this empty or still
 * arriving?" distinction are all decided by the shared ViewModel, so the browser cannot drift into
 * its own answer for any of them.
 *
 * Series, Authors and Narrators are deliberately absent. The shared state already carries them, and
 * they are near-mechanical repeats of this tab once the pattern is proven.
 */
@Composable
fun LibraryPage(
    state: LibraryUiState,
    onEvent: (LibraryUiEvent) -> Unit,
    onOpenBook: (String) -> Unit,
) {
    when (state) {
        is LibraryUiState.Loading -> Div(attrs = { classes("empty") }) { P { Text("Loading…") } }
        is LibraryUiState.Error -> Div(attrs = { classes("empty") }) { P { Text(state.message) } }
        is LibraryUiState.Loaded -> LoadedLibrary(state, onEvent, onOpenBook)
    }
}

@Composable
private fun LoadedLibrary(
    state: LibraryUiState.Loaded,
    onEvent: (LibraryUiEvent) -> Unit,
    onOpenBook: (String) -> Unit,
) {
    Div(attrs = { classes("lib-header") }) {
        H3 { Text("Library") }
        SortControl(state, onEvent)
    }

    if (state.books.isEmpty()) {
        EmptyLibrary(isSyncing = state.isSyncing)
        return
    }

    Div(attrs = { classes("lib-grid") }) {
        state.books.forEach { book ->
            BookCard(
                book = book,
                progress = state.bookProgress[book.id] ?: 0f,
                onOpen = { onOpenBook(book.id.value) },
            )
        }
    }
}

/**
 * Zero books means one of two very different things, and saying the wrong one is worse than saying
 * nothing: a library mid-seed is not an empty library.
 */
@Composable
private fun EmptyLibrary(isSyncing: Boolean) {
    Div(attrs = { classes("empty") }) {
        if (isSyncing) {
            H3 { Text("Syncing your library…") }
            P { Text("Books will appear here as they arrive.") }
        } else {
            H3 { Text("No books yet") }
            P { Text("Add a folder on the server and run a scan.") }
        }
    }
}

@Composable
private fun BookCard(
    book: BookListItem,
    progress: Float,
    onOpen: () -> Unit,
) {
    Div(attrs = {
        classes("lib-card")
        onClick { onOpen() }
    }) {
        Img(
            src = coverUrl(book.id.value),
            attrs = {
                classes("lib-cover")
                alt(book.title)
            },
        )
        Div(attrs = { classes("lib-title") }) { Text(book.title) }
        if (progress > 0f) {
            Div(attrs = { classes("lib-progress") }) {
                Div(attrs = {
                    classes("lib-progress-fill")
                    style { width((progress * PERCENT).percent) }
                })
            }
        }
    }
}

/**
 * Sort category and direction for the Books tab.
 *
 * Both ride [LibraryUiEvent], so the shared ViewModel owns persistence — the browser never stores a
 * sort preference of its own, which is what keeps a reader's ordering the same on every device.
 */
@Composable
private fun SortControl(
    state: LibraryUiState.Loaded,
    onEvent: (LibraryUiEvent) -> Unit,
) {
    Div(attrs = { classes("lib-sort") }) {
        BOOK_SORT_CATEGORIES.forEach { category ->
            Div(attrs = {
                classes("lib-sort-option")
                if (state.booksSortState.category == category) classes("is-active")
                onClick { onEvent(LibraryUiEvent.BooksCategoryChanged(category)) }
            }) { Text(category.label) }
        }
        Div(attrs = {
            classes("lib-sort-direction")
            onClick { onEvent(LibraryUiEvent.BooksDirectionToggled) }
        }) { Text(if (state.booksSortState.direction == SortDirection.ASCENDING) "↑" else "↓") }
    }
}

/**
 * A same-origin relative URL, authenticated by the cookie the browser already holds.
 *
 * Relative rather than absolute on purpose: the server serves this bundle in the normal deployment,
 * and a cookie cannot cross origins anyway — so an absolute URL pointing at a different server would
 * produce an unauthenticated request rather than a working image.
 */
private fun coverUrl(bookId: String): String = "/api/v1/books/$bookId/cover"

private const val PERCENT = 100
