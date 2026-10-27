package com.calypsan.listenup.web.features.library

import com.calypsan.listenup.web.design.RestrictedMarker
import com.calypsan.listenup.web.features.admin.InboxBadgeState
import com.calypsan.listenup.web.design.Cover
import com.calypsan.listenup.web.design.ProgressLook
import com.calypsan.listenup.web.design.ProgressBar
import com.calypsan.listenup.client.presentation.library.BookCardStatus
import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.presentation.library.BookStatusFilter
import com.calypsan.listenup.client.presentation.library.LibraryUiEvent
import com.calypsan.listenup.client.presentation.library.LibraryUiState
import com.calypsan.listenup.client.presentation.library.SortCategory
import com.calypsan.listenup.web.design.EmptyState
import com.calypsan.listenup.web.design.LoadingState
import com.calypsan.listenup.web.design.FacetRow
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.PageHeader
import com.calypsan.listenup.web.design.SortDropdown
import com.calypsan.listenup.web.design.SortStatusRow
import com.calypsan.listenup.web.design.SortStatus
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.design.LibraryFacet
import com.calypsan.listenup.web.design.coverUrl
import com.calypsan.listenup.web.design.ContextMenuHost
import com.calypsan.listenup.web.design.MenuAction
import com.calypsan.listenup.client.presentation.library.bookMenuPrimary
import org.jetbrains.compose.web.attributes.AttrsScope
import org.w3c.dom.HTMLDivElement
import com.calypsan.listenup.web.motion.CoverSurface
import com.calypsan.listenup.web.motion.flyHeroInto
import com.calypsan.listenup.web.motion.recordHeroOrigin
import org.w3c.dom.Element
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * Categories the Books tab sorts by.
 *
 * `SortCategory.entries` also carries categories that only make sense for the Series, Authors and
 * Narrators tabs, so the row is an explicit list rather than the whole enum — a filter by name would
 * silently pick up whatever a future entry happens to be called. In the order board
 * `Library-WebDesktop-Sort` lists them.
 */
internal val BOOK_SORT_CATEGORIES =
    listOf(
        SortCategory.TITLE,
        SortCategory.AUTHOR,
        SortCategory.SERIES,
        SortCategory.ADDED,
        SortCategory.DURATION,
        SortCategory.YEAR,
        SortCategory.RATING,
        SortCategory.LISTENER_RATING,
    )

/**
 * The Books tab of the library.
 *
 * Renders [LibraryUiState] and nothing else — the sort, the progress and the "is this empty or still
 * arriving?" distinction are all decided by the shared ViewModel, so the browser cannot drift into
 * its own answer for any of them.
 *
 * Series, Authors and Narrators tables are deliberately absent — the shared state already carries
 * them, and they are near-mechanical repeats of this tab once the pattern is proven. The people
 * behind those tables are reachable today, though: [onSelectFacet] is this page's half of the
 * facet row it shares with [com.calypsan.listenup.web.features.contributors.ContributorsPage].
 */
@Composable
@Suppress("LongParameterList")
fun LibraryPage(
    state: LibraryUiState,
    onEvent: (LibraryUiEvent) -> Unit,
    onOpenBook: (String) -> Unit,
    onSelectFacet: (LibraryFacet) -> Unit,
    heroBookId: String? = null,
    selecting: Boolean = false,
    selectedIds: Set<String> = emptySet(),
    onToggleSelect: (String) -> Unit = {},
    onStartSelecting: (() -> Unit)? = null,
    /** The held-for-review count and cover fan for the inbox strip; nothing held draws nothing. */
    inbox: InboxBadgeState = InboxBadgeState(),
    onOpenInbox: () -> Unit = {},
    /** Each book's menu on right-click, the Menu key or a long press; null offers none. */
    bookMenu: LibraryBookMenuSource? = null,
) {
    // Header and facet row render in EVERY state, because they are navigation rather than data: a
    // first sync can run for minutes, and hiding the row until the books land would strand a reader
    // at "Loading…" with no way to reach the people already in their library — an error state would
    // strand them for good. Sorting is the exception, and stays with the loaded branch: offering to
    // reorder nothing is an affordance whose only outcome is nothing.
    Div(attrs = { classes("lib-header") }) {
        PageHeader(
            title = "Library",
            // "1,286 books · 41 days of listening": the whole library, whatever the filter.
            subtitle =
                (state as? LibraryUiState.Loaded)
                    ?.takeIf { it.statusCounts.all > 0 }
                    ?.let { libraryCountLine(it.statusCounts.all, it.totalDurationMs) },
        ) {
            // A library with books carries Select and Sort at the end of its status-chip row (board
            // `Library-WebDesktop`, see [LibraryControls]); one with no books has no chips, so its
            // Sort keeps the header.
            // Not while the library is still arriving: there is nothing to sort yet, and the first book would move Sort
            // from here to the chip row below the strip.
            if (state is LibraryUiState.Loaded && state.isEmpty &&
                !state.isLibraryArriving
            ) {
                BookSortControl(state, onEvent, enabled = !selecting)
            }
        }
    }
    FacetRow(active = LibraryFacet.Books, hrefFor = { routeFor(it).toUrl() }, onSelect = onSelectFacet)

    when (state) {
        is LibraryUiState.Loading -> {
            LoadingState()
        }

        is LibraryUiState.Error -> {
            EmptyState(title = "Your library can't be shown", body = state.message) {
                Button(
                    kind = ButtonKind.Secondary,
                    onClick = { onEvent(LibraryUiEvent.RefreshRequested) },
                    attrs = { classes("lib-retry") },
                ) { Text("Try again") }
            }
        }

        is LibraryUiState.Loaded -> {
            LoadedLibrary(
                state = state,
                onEvent = onEvent,
                onOpenBook = onOpenBook,
                heroBookId = heroBookId,
                selecting = selecting,
                selectedIds = selectedIds,
                onToggleSelect = onToggleSelect,
                onStartSelecting = onStartSelecting,
                inbox = inbox,
                onOpenInbox = onOpenInbox,
                bookMenu = bookMenu,
            )
        }
    }
}

@Composable
@Suppress("LongParameterList")
private fun LoadedLibrary(
    state: LibraryUiState.Loaded,
    onEvent: (LibraryUiEvent) -> Unit,
    onOpenBook: (String) -> Unit,
    heroBookId: String?,
    selecting: Boolean,
    selectedIds: Set<String>,
    onToggleSelect: (String) -> Unit,
    onStartSelecting: (() -> Unit)?,
    inbox: InboxBadgeState,
    onOpenInbox: () -> Unit,
    bookMenu: LibraryBookMenuSource?,
) {
    // The shared decision: a scan outranks a library still arriving, and a fresh device's first catch-up counts.
    LibraryStripStatus(strip = state.statusStrip, placement = "is-above-grid")
    // Above the grid and above an empty library alike: an admin whose every new book is held has
    // an empty grid, and the strip is the way in. Selecting turns the grid into a picking surface.
    if (!selecting) LibraryInboxStrip(inbox = inbox, onOpenInbox = onOpenInbox)
    if (!state.isEmpty) {
        LibraryControls(state, onEvent, selecting, onStartSelecting)
    }
    // ⛔ `isEmpty` is the LIBRARY having no books; a filter that matches nothing is `isFilteredEmpty`,
    // and telling that reader "No books yet" would say their library had gone. A library still arriving is not
    // empty either: the strip above says so, over the placeholders its first books will fill.
    if (state.isEmpty && !state.isLibraryArriving) {
        EmptyLibrary()
        return
    }
    if (state.isFilteredEmpty) {
        FilteredEmpty(state.statusFilter) { onEvent(LibraryUiEvent.StatusFilterChanged(BookStatusFilter.ALL)) }
        return
    }

    BooksWithRail(
        books = state.books,
        // The shared letter sections: every platform files a book under the same letter.
        letters = state.bookLetters,
        statusOf = { state.bookStatus[it.id] },
        // ⛔ While selecting, a press picks the book instead of opening it. One gesture, two jobs,
        // decided by the mode — not a second target on every tile in a 1200-book grid.
        onOpenBook = { id -> if (selecting) onToggleSelect(id) else onOpenBook(id) },
        heroBookId = heroBookId,
        selecting = selecting,
        isSelected = { it in selectedIds },
        trailingPlaceholders = if (state.isLibraryArriving) ARRIVING_PLACEHOLDERS else 0,
        // No menu while selecting: the card is a checkbox then, and a press picks it.
        menuOf = bookMenu?.takeUnless { selecting }?.let { source -> { book -> menuFor(book, state, source) } },
    )
}

/** [book]'s menu, leading with what its card says about it (the shared [bookMenuPrimary]). */
private fun menuFor(
    book: BookListItem,
    state: LibraryUiState.Loaded,
    source: LibraryBookMenuSource,
): List<MenuAction> {
    val id = book.id.value
    return libraryBookMenuItems(
        primary = bookMenuPrimary(state.bookStatus[book.id]),
        isAdmin = source.isAdmin,
        actions =
            LibraryBookMenuActions(
                onPrimary = { source.onPlay(id) },
                onAddToShelf = source.onShelveOne?.let { shelve -> { shelve(id) } },
                onShare = source.onShare?.let { share -> { share(id, book.title) } },
                onSelect = { source.onSelectOne(id) },
                onAddToCollection = source.onCollectOne?.let { collect -> { collect(id) } },
                onEditDetails = source.onEdit?.let { edit -> { edit(id) } },
            ),
    )
}

/**
 * Opens [bookId], first recording where its cover is so the detail page's hero can fly in from it.
 *
 * Shared by the pointer and keyboard paths so the flight is not a mouse-only courtesy. The rect is
 * read now rather than later because by the time that hero mounts this element is gone — the grid
 * has unmounted, and a rect read from a detached node is zero.
 */
private fun openWithOrigin(
    bookId: String,
    card: Element?,
    onOpen: () -> Unit,
) {
    card?.querySelector(".lib-cover")?.let { recordHeroOrigin(bookId, CoverSurface.GRID, it) }
    onOpen()
}

/**
 * A library with no books that is not still arriving. Zero books mid-seed is a different thing, and saying the wrong
 * one is worse than saying nothing: the caller gates on `isLibraryArriving`, never `isSyncing`, which reports the
 * CONNECTION — `Connected` for the whole of an initial seed — and so read "No books yet" for the entire
 * pre-first-batch window against a real 1195-book library.
 */
@Composable
private fun EmptyLibrary() {
    EmptyState(title = "No books yet", body = "Add a folder on the server and run a scan.")
}

@Composable
internal fun BookCard(
    book: BookListItem,
    status: BookCardStatus?,
    onOpen: () -> Unit,
    isHero: Boolean = false,
    selecting: Boolean = false,
    isSelected: Boolean = false,
    /** The book's menu (right-click, the Menu key, a long press); null or empty, and while selecting, none. */
    menu: List<MenuAction>? = null,
) {
    if (menu.isNullOrEmpty() || selecting) {
        CardBody(book, status, onOpen, isHero, selecting, isSelected, menuTarget = null)
    } else {
        ContextMenuHost(items = menu, label = "Actions for ${book.title}", anchorSelector = ".lib-cover") {
            CardBody(book, status, onOpen, isHero, selecting, isSelected, menuTarget = { contextMenuTarget() })
        }
    }
}

@Composable
@Suppress("LongParameterList", "LongMethod")
private fun CardBody(
    book: BookListItem,
    status: BookCardStatus?,
    onOpen: () -> Unit,
    isHero: Boolean,
    selecting: Boolean,
    isSelected: Boolean,
    /** Makes the card its menu's target; first, so a long press's click is swallowed before the card opens. */
    menuTarget: (AttrsScope<HTMLDivElement>.() -> Unit)?,
) {
    Div(attrs = {
        menuTarget?.invoke(this)
        classes("lib-card")
        if (selecting && isSelected) classes("on")
        // The dense desktop grid drops the narrator line; the tooltip carries it (spec §2.6).
        attr("title", cardTooltip(book))
        // A click target owes the same affordance to a reader who is not using a mouse. A bare
        // Div is not focusable, so before this the library could not be reached by keyboard at
        // all — and a focus ring had nothing to attach to.
        tabIndex(0)
        // ⛔ While selecting, the card IS a checkbox — same element, different job. Announcing it
        // as a button would tell a screen-reader user the card opens the book, which is exactly
        // what it stops doing.
        attr("role", if (selecting) "checkbox" else "button")
        if (selecting) attr("aria-checked", isSelected.toString())
        onKeyDown { event ->
            if (event.key == "Enter" || event.key == " ") {
                // Space scrolls the page by default, which on a grid means the card the reader
                // just activated jumps away underneath them.
                event.preventDefault()
                openWithOrigin(book.id.value, event.currentTarget as? Element, onOpen)
            }
        }
        onClick { event ->
            openWithOrigin(book.id.value, event.currentTarget as? Element, onOpen)
        }
    }) {
        if (selecting) SelectionTick(isSelected)
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
        CardCover(book, status, flyBack, selecting)
        Div(attrs = { classes("lib-title") }) { Text(book.title) }
        // Rendered even when empty, and likewise the narrator line below: the grid is virtualised,
        // and that only works because every card is exactly the same height. A card that dropped
        // its author line would be shorter than its neighbours and the row arithmetic would drift.
        Div(attrs = { classes("lib-author") }) { Text(book.authors.joinToString(", ") { it.name }) }
        // A no-break space when there is no narrator, for the same reason. Hidden per grid (by a
        // container query) below 160px, where the tooltip carries it instead.
        Div(attrs = { classes("lib-narrator") }) {
            Text(if (book.narratorNames.isBlank()) NO_BREAK_SPACE else "Read by ${book.narratorNames}")
        }
        Div(attrs = {
            classes("lib-meta")
            if (status is BookCardStatus.InProgress) classes("is-progress")
        }) { Text(cardLastLine(status, book.duration)) }
    }
}

/**
 * The selection tick on a card.
 *
 * It rides the card rather than sitting beside it: a 1200-book grid has no room for a second
 * target per tile, and a card that is a checkbox should look like one.
 */
@Composable
private fun SelectionTick(isSelected: Boolean) {
    Div(attrs = {
        classes("lib-tick")
        if (isSelected) classes("on")
    }) { if (isSelected) Icon(WebIcon.Check, size = TICK_ICON) }
}

/**
 * The card's artwork, or — when the server holds none — the same titled tile every other page draws
 * for that book: this is the shared [Cover], fluid to the grid's column.
 *
 * Decorative, because the title is the card's own text directly below, and naming it in the picture
 * too made a screen reader say every book twice. Lazy, because a 1200-book library otherwise pulls
 * 1200 covers on first paint; the `srcset` leaves the rung to the browser, which knows the device's
 * pixel ratio and we do not.
 */
@Composable
private fun CardCover(
    book: BookListItem,
    status: BookCardStatus?,
    flyBack: (org.jetbrains.compose.web.attributes.AttrsScope<*>) -> Unit,
    selecting: Boolean,
) {
    Cover(
        title = book.title,
        imageUrl = coverUrl(book.id.value, book.coverHash, GRID_RUNG),
        size = null,
        radius = CARD_COVER_RADIUS,
        decorative = true,
        srcset = coverSrcset(book.id.value, book.coverHash),
        attrs = {
            classes("lib-cover")
            flyBack(this)
        },
        // Inside the cover, so it lifts with it on hover; clear of the selection tick while selecting.
        overlay = {
            when (status) {
                is BookCardStatus.InProgress -> {
                    ProgressBar(
                        value = status.fraction,
                        label = "Listening progress",
                        // The house cover-tile idiom: 4px along the art's bottom edge.
                        look = ProgressLook.Overlay,
                    )
                }

                is BookCardStatus.Finished -> {
                    Span(attrs = {
                        classes("lib-done")
                        attr("role", "img")
                        attr("aria-label", "Finished")
                    }) { Icon(WebIcon.Check, size = DONE_ICON) }
                }

                else -> {
                    Unit
                }
            }
            RestrictedMarker(book.id.value, shifted = selecting)
        },
    )
}

/**
 * The count, Select and Filter and sort on one row (board `Lib-Web-After`): the status lives in the Sort menu. Only
 * over a library that has books: offering to narrow or reorder nothing is no offer at all. While selecting, Select
 * steps aside and Sort stays, stilled: filtering mid-selection would change what is being picked.
 */
@Composable
private fun LibraryControls(
    state: LibraryUiState.Loaded,
    onEvent: (LibraryUiEvent) -> Unit,
    selecting: Boolean,
    onStartSelecting: (() -> Unit)?,
) {
    Div(attrs = { classes("lib-controls") }) {
        Div(attrs = { classes("lib-controls-end") }) {
            // A phone's count, beside Sort (spec §3.1.7); the desktop says it in the page header instead.
            Span(attrs = { classes("lib-count") }) {
                Text(if (state.statusCounts.all == 1) "1 book" else "${groupedCount(state.statusCounts.all)} books")
            }
            // Offered only once there is something to select: arming selection over an empty grid
            // is an affordance whose only outcome is nothing.
            if (state.books.isNotEmpty() && !selecting && onStartSelecting != null) {
                Button(
                    kind = ButtonKind.Secondary,
                    onClick = { onStartSelecting() },
                    attrs = { classes("lib-select") },
                ) { Text("Select") }
            }
            BookSortControl(state, onEvent, enabled = !selecting)
        }
    }
}

/**
 * Filter and sort for the Books tab: the reading state (the Show group), sort category, direction and Ignore articles.
 *
 * All ride [LibraryUiEvent], so the shared ViewModel owns persistence — the browser never stores a
 * sort preference of its own, which is what keeps a reader's ordering the same on every device.
 * Ignore articles is offered only on a title sort, the one it changes.
 */
@Composable
private fun BookSortControl(
    state: LibraryUiState.Loaded,
    onEvent: (LibraryUiEvent) -> Unit,
    enabled: Boolean,
) {
    SortDropdown(
        noun = "books",
        categories = BOOK_SORT_CATEGORIES,
        state = state.booksSortState,
        onCategory = { onEvent(LibraryUiEvent.BooksCategoryChanged(it)) },
        onToggleDirection = { onEvent(LibraryUiEvent.BooksDirectionToggled) },
        ignoreArticles = state.ignoreTitleArticles.takeIf { state.booksSortState.category == SortCategory.TITLE },
        onToggleIgnoreArticles = { onEvent(LibraryUiEvent.ToggleIgnoreTitleArticles) },
        status =
            SortStatus(
                BookStatusFilter.entries.map { filter ->
                    SortStatusRow(
                        label = filter.webLabel(),
                        count = groupedCount(state.statusCounts.countFor(filter)),
                        isSelected = filter == state.statusFilter,
                        onSelect = { onEvent(LibraryUiEvent.StatusFilterChanged(filter)) },
                    )
                },
            ),
        enabled = enabled,
    )
}

/** The same cover at both rungs, for the browser to choose between by pixel density. */
private fun coverSrcset(
    bookId: String,
    coverHash: String?,
): String =
    "${coverUrl(bookId, coverHash, GRID_RUNG)} 1x, " +
        "${coverUrl(bookId, coverHash, GRID_RUNG_DENSE)} 2x"

private const val TICK_ICON = 14

/** Book-shaped blanks after the last book while the library is still arriving (spec boards `*-FirstSync`). */
private const val ARRIVING_PLACEHOLDERS = 4

private const val DONE_ICON = 11

private const val NO_BREAK_SPACE = "\u00A0"

/** The grid's tiles are at most a little over 150px wide (108px on desktop); 300 covers one at 1x with room. */
private const val GRID_RUNG = 300

/** The rung a 2x display needs for the same tile. Also the largest the ladder offers. */
private const val GRID_RUNG_DENSE = 600

/** The house corner (`--r-md`), shared with a contributor's tiles, so a book's tile is one shape everywhere. */
private const val CARD_COVER_RADIUS = 12
