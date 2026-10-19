package com.calypsan.listenup.web.features.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.presentation.books.BookMultiSelectEvent
import com.calypsan.listenup.client.presentation.library.BookStatusFilter
import com.calypsan.listenup.client.presentation.library.LibraryUiEvent
import com.calypsan.listenup.web.design.BulkAction
import com.calypsan.listenup.web.design.BulkBar
import com.calypsan.listenup.web.design.booksSelectedLabel
import com.calypsan.listenup.web.design.LibraryFacet
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.features.admin.InboxBadgeState
import com.calypsan.listenup.web.features.books.BookSelection
import com.calypsan.listenup.web.features.books.OpenMultiSelect
import com.calypsan.listenup.web.features.books.PickerTarget
import com.calypsan.listenup.web.features.books.SelectionPicker
import com.calypsan.listenup.web.features.books.LocalSelectionHandOff
import com.calypsan.listenup.web.features.books.bookCountLabel
import com.calypsan.listenup.web.features.books.isActive
import com.calypsan.listenup.web.features.books.selectedIds
import com.calypsan.listenup.web.features.search.SearchRouteContent
import com.calypsan.listenup.web.features.serieslist.SeriesListPage
import com.calypsan.listenup.web.features.shelf.ShelfRouteContent
import com.calypsan.listenup.web.nav.CONTRIBUTORS_KEY
import com.calypsan.listenup.web.nav.LIBRARY_KEY
import com.calypsan.listenup.web.nav.NARRATOR_ROLE_VALUE
import com.calypsan.listenup.web.nav.IN_PROGRESS_STATUS
import com.calypsan.listenup.web.nav.NEWEST_SORT
import com.calypsan.listenup.web.nav.SORT_QUERY_KEY
import com.calypsan.listenup.web.nav.STATUS_QUERY_KEY
import com.calypsan.listenup.web.nav.ROLE_QUERY_KEY
import com.calypsan.listenup.web.nav.Route
import com.calypsan.listenup.web.nav.Router
import com.calypsan.listenup.web.nav.Routes
import com.calypsan.listenup.web.nav.rememberSession
import com.calypsan.listenup.web.shell.RouteContent
import com.calypsan.listenup.web.shell.animatedLibrary

/**
 * `/library/series` — every series in the library, over the shell's own library session.
 *
 * Extracted for the reason [ShelfRouteContent] and [SearchRouteContent] were: [RouteContent]'s chain
 * is a cognitive-complexity budget every new route spends from, and this one pushed it over.
 */
@Composable
internal fun SeriesListRoute(
    librarySession: LibrarySession,
    router: Router,
) {
    SeriesListPage(
        state = librarySession.state.collectAsState().value,
        onEvent = librarySession.onEvent,
        onOpenSeries = { id -> router.navigate(Routes.series(id)) },
        onSelectFacet = { facet -> router.navigate(routeFor(facet)) },
    )
}

/**
 * The library grid, and picking books out of it.
 *
 * ⛔ Selection is page state, not a route. It is a *mode* the reader is in for a few seconds, and
 * putting it in the URL would make Back un-pick books one at a time and make a shared link carry
 * somebody else's selection. Book Detail's chapter selection rides the URL for the opposite reason:
 * it survives a reload and is worth linking to.
 */
@Composable
internal fun LibraryRouteContent(
    librarySession: LibrarySession,
    inbox: InboxBadgeState,
    openMultiSelect: OpenMultiSelect,
    router: Router,
    heroBookId: String?,
    onHeroBookIdChange: (String) -> Unit,
    onToast: (String) -> Unit,
    /** The URL's query: a one-visit sort or status filter landing (spec §6.4). */
    query: Map<String, String> = emptyMap(),
) {
    // Once applied, each landing leaves the URL, so Back to here does not force it again over the reader's own choice.
    LibrarySortLanding(query[SORT_QUERY_KEY], librarySession.onEvent) {
        router.replace(Route(listOf(LIBRARY_KEY), query - SORT_QUERY_KEY))
    }
    LibraryStatusLanding(query[STATUS_QUERY_KEY], librarySession.onEvent) {
        router.replace(Route(listOf(LIBRARY_KEY), query - STATUS_QUERY_KEY))
    }
    BookSelectionScaffold(openMultiSelect, router, onToast) { selection ->
        LibraryPage(
            state = animatedLibrary(librarySession),
            onEvent = librarySession.onEvent,
            onOpenBook = { id ->
                onHeroBookIdChange(id)
                router.navigate(Routes.Book.detail(id))
            },
            onSelectFacet = { facet -> router.navigate(routeFor(facet)) },
            heroBookId = heroBookId,
            selecting = selection.isSelecting,
            selectedIds = selection.selectedIds,
            onToggleSelect = selection.onToggle,
            onStartSelecting = selection.onStart,
            inbox = inbox,
            onOpenInbox = { router.navigate(Routes.Admin.inbox) },
        )
    }
}

/**
 * `/library?sort=added-desc` (Discover's "Newest in Library →"): the Books, newest first, for this
 * visit only; the reader's saved sort is untouched (plan Q6). Any other value, or none, asks nothing. [onApplied] runs
 * once the sort is asked for, so the route can drop the query.
 */
@Composable
internal fun LibrarySortLanding(
    sortQuery: String?,
    onEvent: (LibraryUiEvent) -> Unit,
    onApplied: () -> Unit = {},
) {
    val currentOnApplied by rememberUpdatedState(onApplied)
    LaunchedEffect(sortQuery) {
        if (sortQuery == NEWEST_SORT) {
            onEvent(LibraryUiEvent.BooksShownNewestFirst)
            currentOnApplied()
        }
    }
}

/**
 * `/library?status=in-progress` (Home's "In Library →"): the Books filtered to In progress. The filter is the
 * session's own, so [rememberingStatusFilter] keeps it for the visit. Any other value, or none, asks nothing.
 * [onApplied] runs once the filter is asked for, so the route can drop the query.
 */
@Composable
internal fun LibraryStatusLanding(
    statusQuery: String?,
    onEvent: (LibraryUiEvent) -> Unit,
    onApplied: () -> Unit = {},
) {
    val currentOnApplied by rememberUpdatedState(onApplied)
    LaunchedEffect(statusQuery) {
        if (statusQuery == IN_PROGRESS_STATUS) {
            onEvent(LibraryUiEvent.StatusFilterChanged(BookStatusFilter.IN_PROGRESS))
            currentOnApplied()
        }
    }
}

/**
 * The selection machinery every book surface shares: the session, the bar, the destination pickers,
 * and the navigation an Edit destination performs.
 *
 * Extracted from [LibraryRouteContent] when Home and Discover needed the same thing. Both natives
 * already factored it the same way — Android's `BookSelectionScaffold`, iOS's
 * `BookSelectionScreenChrome` — and for the same reason: sixty lines of session, bar and picker per
 * surface is three copies to keep in step.
 *
 * [content] receives a [BookSelection] rather than three loose parameters, so a card consults one
 * object and a new selectable surface adds no new signatures.
 */
@Composable
internal fun BookSelectionScaffold(
    openMultiSelect: OpenMultiSelect,
    router: Router,
    onToast: (String) -> Unit,
    content: @Composable (BookSelection) -> Unit,
) {
    val session = rememberSession { openMultiSelect() }
    val handOff = LocalSelectionHandOff.current
    // Back from the bulk editor: this page's new session picks up where the last one left off.
    LaunchedEffect(session) {
        handOff.takeFor(router.current)?.let { ids ->
            session.onEnter()
            ids.forEach(session.onToggle)
        }
    }

    val mode = session.selectionMode.collectAsState().value
    val selected = mode.selectedIds()
    val isAdmin = session.adminAccess.collectAsState().value
    var picking by remember { mutableStateOf<SelectionDestination?>(null) }
    // A row menu's "Add {book} to a shelf" selects that one book and opens the picker; when the picker
    // closes, by dismissal or success, the selection it made ends with it (plan C5, iOS's rule).
    var shelvingOne by remember { mutableStateOf(false) }

    fun closePicker() {
        picking = null
        if (shelvingOne) {
            shelvingOne = false
            session.onExit()
        }
    }

    LaunchedEffect(session) {
        session.events.collect { event ->
            // The picker closes on success, never on failure: a failed add keeps the selection so
            // the reader can try a different destination rather than rebuild it.
            closePicker()
            onToast(confirmationFor(event))
        }
    }

    content(
        BookSelection(
            isSelecting = mode.isActive(),
            selectedIds = selected,
            onToggle = session.onToggle,
            onStart = session.onEnter,
            onShelveOne = { bookId ->
                session.onEnter()
                session.onToggle(bookId)
                shelvingOne = true
                picking = SelectionDestination.Shelf
            },
        ),
    )

    if (mode.isActive()) {
        BulkBar(
            count = selected.size,
            label = booksSelectedLabel(selected.size),
            actions = bulkActions(selected.isNotEmpty(), isAdmin) { picking = it },
            onClear = session.onExit,
        )
    }

    when (picking) {
        null -> {
            Unit
        }

        // Not a picker: the editor is a page, because forty books' worth of fields and a
        // "what will change" panel do not belong in a dialog over the grid.
        SelectionDestination.Edit -> {
            LaunchedEffect(selected) {
                picking = null
                // Held so backing out of the editor returns to these picks, as on Android.
                handOff.hold(router.current, selected)
                router.navigate(
                    Routes.bulkEdit(selected),
                )
            }
        }

        SelectionDestination.Shelf -> {
            SelectionPicker(
                title = "Add to shelf",
                count = selected.size,
                targets =
                    session.myShelves
                        .collectAsState()
                        .value
                        .map { PickerTarget(it.id.value, it.name, null) },
                emptyMessage = "You have no shelves yet.",
                createLabel = "Or make a new shelf",
                isBusy = session.addingToShelf.collectAsState().value,
                onPick = session.onAddToShelf,
                onCreate = session.onCreateShelfAndAdd,
                onDismiss = ::closePicker,
            )
        }

        SelectionDestination.Collection -> {
            SelectionPicker(
                title = "Add to collection",
                count = selected.size,
                targets =
                    session.collections.collectAsState().value.map {
                        PickerTarget(it.id, it.name, bookCountLabel(it.bookCount))
                    },
                emptyMessage = "There are no collections yet.",
                createLabel = "Or make a new collection",
                isBusy = session.addingToCollection.collectAsState().value,
                onPick = session.onAddToCollection,
                onCreate = session.onCreateCollectionAndAdd,
                onDismiss = { picking = null },
            )
        }
    }
}

/**
 * What Home and Discover both need beyond their own session.
 *
 * Three values that travel together to exactly two call sites, bundled so [RouteContent]'s chain
 * stays one glance of routing — the same budget that pushed [ShelfRouteContent] and
 * [SearchRouteContent] out. ⛔ Not the start of a general opener bundle: it is scoped to the two
 * book feeds and grows only if a third feed appears.
 */
internal class FeedSelection(
    val openMultiSelect: OpenMultiSelect,
    val onHeroBookIdChange: (String) -> Unit,
    val onToast: (String) -> Unit,
)

/** Where a selection can be sent. */
private enum class SelectionDestination { Edit, Shelf, Collection }

/**
 * The actions the bar offers.
 *
 * ⛔ Nothing selected means no actions, not disabled ones: the bar is already saying "0 selected",
 * and a row of greyed-out verbs under that says nothing the count has not. Edit and collections
 * are admin-only: collections by the same rule Book Detail's own collection picker follows.
 */
private fun bulkActions(
    hasSelection: Boolean,
    isAdmin: Boolean,
    onPick: (SelectionDestination) -> Unit,
): List<BulkAction> {
    if (!hasSelection) return emptyList()
    return buildList {
        // Editing a book's metadata is an admin's job, as on Android, whose toolbar offers Edit only
        // when the (admin) host passes it.
        if (isAdmin) add(BulkAction("Edit", WebIcon.Pencil) { onPick(SelectionDestination.Edit) })
        add(BulkAction("Add to shelf", WebIcon.Bookmark) { onPick(SelectionDestination.Shelf) })
        if (isAdmin) add(BulkAction("Add to collection", WebIcon.Layers) { onPick(SelectionDestination.Collection) })
    }
}

/**
 * What a completed bulk action says: the `selection.added_to_*` and `selection.created_with_books_*`
 * words, the same sentence Android and iOS show. The number is what the shelf or collection gained.
 */
internal fun confirmationFor(event: BookMultiSelectEvent): String =
    when (event) {
        is BookMultiSelectEvent.BooksAddedToShelf -> {
            when (event.count) {
                0 -> "Already on that shelf"
                1 -> "1 book added to shelf"
                else -> "${event.count} books added to shelf"
            }
        }

        is BookMultiSelectEvent.BooksAddedToCollection -> {
            when (event.count) {
                0 -> "Already in that collection"
                1 -> "1 book added to collection"
                else -> "${event.count} books added to collection"
            }
        }

        is BookMultiSelectEvent.ShelfCreatedAndBooksAdded -> {
            createdWithBooks(event.shelfName, event.bookCount)
        }

        is BookMultiSelectEvent.CollectionCreatedAndBooksAdded -> {
            createdWithBooks(event.collectionName, event.bookCount)
        }
    }

private fun createdWithBooks(
    name: String,
    count: Int,
): String = if (count == 1) "Created \"$name\" with 1 book" else "Created \"$name\" with $count books"

/** Where selecting [facet] in the shared facet row navigates to. */
internal fun routeFor(facet: LibraryFacet): Route =
    when (facet) {
        LibraryFacet.Books -> {
            Routes.library
        }

        LibraryFacet.Series -> {
            Routes.librarySeries
        }

        LibraryFacet.Authors -> {
            Routes.libraryContributors
        }

        LibraryFacet.Narrators -> {
            Route(
                listOf(LIBRARY_KEY, CONTRIBUTORS_KEY),
                mapOf(ROLE_QUERY_KEY to NARRATOR_ROLE_VALUE),
            )
        }
    }
