package com.calypsan.listenup.web.features.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.presentation.library.LibraryUiEvent
import com.calypsan.listenup.client.presentation.library.LibraryUiState
import com.calypsan.listenup.client.presentation.library.SortCategory
import com.calypsan.listenup.client.presentation.library.SortDirection
import com.calypsan.listenup.client.util.nameLetter
import com.calypsan.listenup.client.util.sortLetter
import com.calypsan.listenup.web.design.coverUrl
import com.calypsan.listenup.web.motion.CoverSurface
import com.calypsan.listenup.web.motion.flyHeroInto
import com.calypsan.listenup.web.motion.recordHeroOrigin
import org.w3c.dom.Element
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
    heroBookId: String? = null,
) {
    when (state) {
        is LibraryUiState.Loading -> Div(attrs = { classes("empty") }) { P { Text("Loading…") } }
        is LibraryUiState.Error -> Div(attrs = { classes("empty") }) { P { Text(state.message) } }
        is LibraryUiState.Loaded -> LoadedLibrary(state, onEvent, onOpenBook, heroBookId)
    }
}

@Composable
private fun LoadedLibrary(
    state: LibraryUiState.Loaded,
    onEvent: (LibraryUiEvent) -> Unit,
    onOpenBook: (String) -> Unit,
    heroBookId: String?,
) {
    Div(attrs = { classes("lib-header") }) {
        H3 { Text("Library") }
        SortControl(state, onEvent)
    }

    if (state.books.isEmpty()) {
        EmptyLibrary(isBuilding = state.isBuildingInitialLibrary)
        return
    }

    VirtualBookGrid(
        books = state.books,
        letterOf = { it.sectionLetter(state.booksSortState.category, state.ignoreTitleArticles) },
        progressOf = { state.bookProgress[it.id] ?: 0f },
        onOpenBook = onOpenBook,
        heroBookId = heroBookId,
    )
}

/**
 * The letter this book files under for the given sort, or null when the sort is not alphabetical.
 *
 * Delegates to the shared [sortLetter] / [nameLetter] rules rather than taking a first character
 * here: those are what Android groups by and what the Swift mirror follows, and a fourth private
 * copy of "which letter is this?" is exactly how three platforms end up disagreeing about where
 * *The Hobbit* belongs. Added and Duration return null — a letter rail over a date sort would label
 * runs of books with letters that mean nothing.
 */
private fun BookListItem.sectionLetter(
    category: SortCategory,
    ignoreTitleArticles: Boolean,
): Char? =
    when (category) {
        SortCategory.TITLE -> title.sortLetter(ignoreTitleArticles)
        SortCategory.AUTHOR -> authors.firstOrNull()?.name.nameLetter()
        else -> null
    }

/**
 * Zero books means one of two very different things, and saying the wrong one is worse than saying
 * nothing: a library mid-seed is not an empty library.
 *
 * Driven by `isBuildingInitialLibrary` rather than `isSyncing` deliberately. `isSyncing` reports the
 * CONNECTION, which is `Connected` for the whole of an initial seed — so this branch read "No books
 * yet" for the entire pre-first-batch window against a real 1195-book library.
 */
@Composable
private fun EmptyLibrary(isBuilding: Boolean) {
    Div(attrs = { classes("empty") }) {
        if (isBuilding) {
            H3 { Text("Syncing your library…") }
            P { Text("Books will appear here as they arrive.") }
        } else {
            H3 { Text("No books yet") }
            P { Text("Add a folder on the server and run a scan.") }
        }
    }
}

@Composable
internal fun BookCard(
    book: BookListItem,
    progress: Float,
    onOpen: () -> Unit,
    isHero: Boolean = false,
) {
    // A library of any size has books the server holds no artwork for, and a bare <img> renders
    // those as a broken-image icon. The book detail page already falls back to a titled tile; this
    // is the same treatment, so one missing cover does not look like a broken page.
    var coverFailed by remember(book.id) { mutableStateOf(false) }

    Div(attrs = {
        classes("lib-card")
        onClick { event ->
            // Where this cover is *right now*, so the detail page's hero can fly in from here.
            // Recorded at click time because by the time that hero mounts, this element is gone —
            // the grid has unmounted and a rect read from a detached node is zero.
            (event.currentTarget as? Element)
                ?.querySelector(".lib-cover")
                ?.let { recordHeroOrigin(book.id.value, CoverSurface.GRID, it) }
            onOpen()
        }
    }) {
        // The return leg of the flight: this is the tile the reader last opened, so when it mounts
        // it flies in from wherever the detail hero was standing. The outbound leg is recorded in
        // the click handler above; the two are symmetric.
        val flyBack: (org.jetbrains.compose.web.attributes.AttrsScope<*>) -> Unit = { scope ->
            if (isHero) {
                scope.ref { element ->
                    flyHeroInto(book.id.value, CoverSurface.GRID, element)
                    onDispose { }
                }
            }
        }
        if (coverFailed) {
            Div(attrs = {
                classes("lib-cover", "lib-cover-fallback")
                flyBack(this)
            }) { Text(book.title) }
        } else {
            Img(
                src = coverUrl(book.id.value, book.coverHash, GRID_RUNG),
                attrs = {
                    classes("lib-cover")
                    flyBack(this)
                    alt(book.title)
                    // Which rung a display needs is the browser's call, not ours — it knows the
                    // device pixel ratio and we do not. Stating both lets a 1x screen take 300px
                    // and a Retina one take 600px from the same markup.
                    attr("srcset", coverSrcset(book.id.value, book.coverHash))
                    // The browser fetches only what the reader approaches, and decodes off the main
                    // thread. Without these a 1200-book library pulls 1200 covers on first paint.
                    attr("loading", "lazy")
                    attr("decoding", "async")
                    addEventListener("error") { coverFailed = true }
                },
            )
        }
        Div(attrs = { classes("lib-title") }) { Text(book.title) }
        // Rendered even when empty, and likewise the progress rail below: the grid is virtualised,
        // and that only works because every card is exactly the same height. A card that dropped
        // its author line would be shorter than its neighbours and the row arithmetic would drift.
        Div(attrs = { classes("lib-author") }) { Text(book.authors.joinToString(", ") { it.name }) }
        run {
            Div(attrs = {
                classes("lib-progress")
                // Holds its row so every card is the same height, but shows nothing until there is
                // progress to show — a rail on an unstarted book would claim the reader had begun it.
                if (progress <= 0f) classes("is-empty")
            }) {
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

/** The same cover at both rungs, for the browser to choose between by pixel density. */
private fun coverSrcset(
    bookId: String,
    coverHash: String?,
): String =
    "${coverUrl(bookId, coverHash, GRID_RUNG)} 1x, " +
        "${coverUrl(bookId, coverHash, GRID_RUNG_DENSE)} 2x"

private const val PERCENT = 100

/** The grid's tiles are `minmax(190px, 1fr)`; 300 is the smallest rung that covers one at 1x. */
private const val GRID_RUNG = 300

/** The rung a 2x display needs for the same tile. Also the largest the ladder offers. */
private const val GRID_RUNG_DENSE = 600
