package com.calypsan.listenup.web.features.bookdetail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailNavAction
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailUiState
import com.calypsan.listenup.client.presentation.bookdetail.BookRatingsEvent
import com.calypsan.listenup.client.presentation.bookdetail.BookReadersUiState
import com.calypsan.listenup.client.presentation.bookedit.BookEditNavAction
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.web.design.ShowActionToast
import com.calypsan.listenup.web.design.ToastAction
import com.calypsan.listenup.web.design.ToastTone
import com.calypsan.listenup.web.features.bookedit.BookEditPage
import com.calypsan.listenup.web.features.bookedit.BookEditSession
import com.calypsan.listenup.web.features.bookedit.OpenBookEdit
import com.calypsan.listenup.web.features.chaptereditor.ChapterEditorRoute
import com.calypsan.listenup.web.features.chaptereditor.OpenChapterEditor
import com.calypsan.listenup.web.features.chaptereditor.parseSelection
import com.calypsan.listenup.web.features.hardcover.HardcoverMatchRoute
import com.calypsan.listenup.web.features.hardcover.OpenBookHardcover
import com.calypsan.listenup.web.features.hardcover.OpenHardcoverMatch
import com.calypsan.listenup.web.features.match.BookMatchReceipt
import com.calypsan.listenup.web.features.match.BookMatchRoute
import com.calypsan.listenup.web.features.match.MatchDetailsGraph
import com.calypsan.listenup.web.features.match.MatchView
import com.calypsan.listenup.web.features.nowplaying.PlaybackSession
import com.calypsan.listenup.web.features.ratings.BookRatingsSession
import com.calypsan.listenup.web.features.ratings.OpenBookRatings
import com.calypsan.listenup.web.features.readers.OpenBookReaders
import com.calypsan.listenup.web.features.readers.ReadersPage
import com.calypsan.listenup.web.features.settings.AccountRouteContent
import com.calypsan.listenup.web.features.shelf.ShelfRouteContent
import com.calypsan.listenup.web.nav.CHAPTERS_KEY
import com.calypsan.listenup.web.nav.COMPARE_VIEW
import com.calypsan.listenup.web.nav.HARDCOVER_KEY
import com.calypsan.listenup.web.nav.MATCH_KEY
import com.calypsan.listenup.web.nav.READERS_KEY
import com.calypsan.listenup.web.nav.Route
import com.calypsan.listenup.web.nav.Router
import com.calypsan.listenup.web.nav.Routes
import com.calypsan.listenup.web.nav.VIEW_QUERY_KEY
import com.calypsan.listenup.web.nav.editTargetOf
import com.calypsan.listenup.web.nav.rememberSession
import com.calypsan.listenup.web.shell.RouteContent
import com.calypsan.listenup.web.shell.nowMs
import kotlinx.coroutines.launch
import com.calypsan.listenup.web.nav.SECTION_QUERY_KEY
import com.calypsan.listenup.web.nav.READERS_SECTION

/**
 * Opens a Book Detail session for [bookId] and collects it.
 */
@Composable
private fun bookDetailSession(
    bookId: String,
    openBookDetail: OpenBookDetail,
): BookDetailSession {
    val session = rememberSession(bookId) { openBookDetail(bookId) }
    return session
}

/**
 * An open Book Edit session, closed when the route leaves it.
 *
 * The ViewModel's [BookEditNavAction]s are honoured here rather than in the page, because they are
 * navigation and the page is deliberately pure: `NavigateBack` fires on Cancel *and* on a
 * successful Save, so the reader lands back on the book they were editing either way.
 */
@Composable
private fun bookEditState(
    bookId: String,
    openBookEdit: OpenBookEdit,
    onLeave: () -> Unit,
): BookEditSession {
    val session = rememberSession(bookId) { openBookEdit(bookId) }
    LaunchedEffect(session) {
        session.navActions.collect { action ->
            when (action) {
                is BookEditNavAction.NavigateBack -> onLeave()

                // The save-success message has nowhere to go yet: the web shell has no snackbar.
                // Swallowed deliberately rather than dropped silently — see W-follow-ups.
                is BookEditNavAction.ShowSaveSuccess -> Unit
            }
        }
    }
    return session
}

/**
 * `/book/{id}` and `/book/{id}/edit` — one book, and the form that changes it.
 *
 * Extracted for the same reason [ShelfRouteContent] and [AccountRouteContent] were: a family of
 * routes that share an id belongs in one place, and [RouteContent]'s chain is a cognitive-
 * complexity budget that every new route spends from. The edit form is checked first because it
 * is the more specific URL — `/book/42/edit` is also a `/book/42`.
 */
@Composable
internal fun BookRouteContent(
    bookId: String,
    router: Router,
    route: Route,
    openBookDetail: OpenBookDetail,
    openBookEdit: OpenBookEdit,
    openChapterEditor: OpenChapterEditor,
    matchDetails: MatchDetailsGraph,
    openBookReaders: OpenBookReaders,
    openBookRatings: OpenBookRatings,
    openHardcoverMatch: OpenHardcoverMatch,
    openBookHardcover: OpenBookHardcover,
    playback: PlaybackSession,
    onToast: (String) -> Unit,
    onActionToast: ShowActionToast,
    currentUserId: String?,
) {
    val editingBookId = route.editTargetOf(bookId)
    // `/book/{id}/chapters` — a route of its own, for the reason `/book/{id}/edit` is one, and one
    // more: the editor holds unsaved work, so it has to be somewhere Back can leave.
    val chapteringBookId = bookId.takeIf { route.segments.getOrNull(2) == CHAPTERS_KEY }
    // `/book/{id}/match` — Match details: Find, Review and Apply for one book; `?view=compare` is its
    // Compare editions page.
    val matchingBookId = bookId.takeIf { route.segments.getOrNull(2) == MATCH_KEY }
    // `/book/{id}/readers` — the whole readership, where the side panel's "See all" leads.
    val readersBookId = bookId.takeIf { route.segments.getOrNull(2) == READERS_KEY }
    // `/book/{id}/hardcover` — Find on Hardcover, where the Hardcover panel's Change match leads.
    val hardcoverBookId = bookId.takeIf { route.segments.getOrNull(2) == HARDCOVER_KEY }

    if (hardcoverBookId != null) {
        HardcoverMatchRoute(
            router = router,
            openHardcoverMatch = openHardcoverMatch,
            bookId = hardcoverBookId,
            onToast = onToast,
            onActionToast = onActionToast,
        )
        return
    }

    if (readersBookId != null) {
        ReadersPage(
            state = bookReadersState(readersBookId, openBookReaders),
            bookTitle = bookTitleOf(bookDetailSession(readersBookId, openBookDetail).state.collectAsState().value),
            nowMs = nowMs(),
            onOpenProfile = { id -> router.navigate(Routes.profile(id)) },
            onOpenBook = { router.navigate(Routes.Book.detail(readersBookId)) },
        )
        return
    }

    if (matchingBookId != null) {
        val here = Routes.Book.match(matchingBookId)
        BookMatchRoute(
            bookId = matchingBookId,
            graph = matchDetails,
            viewerId = currentUserId,
            view = if (route.query[VIEW_QUERY_KEY] == COMPARE_VIEW) MatchView.Compare else MatchView.Find,
            // Replace, both ways: Compare is a view of the same results, like a tab, and Back should
            // leave Match details rather than walk between its two views.
            onOpenCompare = { router.replace(Route(here.segments, mapOf(VIEW_QUERY_KEY to COMPARE_VIEW))) },
            onCloseCompare = { router.replace(here) },
            onOpenLibrary = { router.navigate(Routes.library) },
            onOpenBook = { router.navigate(Routes.Book.detail(matchingBookId)) },
            // After Apply, back to the book: its receipt says what changed and offers Undo.
            onApplied = { router.navigate(Routes.Book.detail(matchingBookId)) },
        )
        return
    }

    if (chapteringBookId != null) {
        ChapterEditorRoute(
            router = router,
            openChapterEditor = openChapterEditor,
            bookId = chapteringBookId,
            playback = playback,
        )
        return
    }

    if (editingBookId != null) {
        val editSession =
            bookEditState(
                bookId = editingBookId,
                openBookEdit = openBookEdit,
                // The edit page has already asked, or the draft was saved: do not ask twice.
                onLeave = { router.navigate(Routes.Book.detail(editingBookId), force = true) },
            )
        BookEditPage(
            state = editSession.state.collectAsState().value,
            onEvent = editSession.onEvent,
            onOpenLibrary = { router.navigate(Routes.library) },
            onOpenBook = { router.navigate(Routes.Book.detail(editingBookId)) },
        )
        return
    }

    BookDetailRoute(
        bookId = bookId,
        router = router,
        route = route,
        openBookDetail = openBookDetail,
        matchDetails = matchDetails,
        openBookReaders = openBookReaders,
        openBookRatings = openBookRatings,
        openBookHardcover = openBookHardcover,
        playback = playback,
        onToast = onToast,
        onActionToast = onActionToast,
    )
}

/** `/book/{id}` itself — the book, its panels, and every way out of them. */
@Composable
private fun BookDetailRoute(
    bookId: String,
    router: Router,
    route: Route,
    openBookDetail: OpenBookDetail,
    matchDetails: MatchDetailsGraph,
    openBookReaders: OpenBookReaders,
    openBookRatings: OpenBookRatings,
    openBookHardcover: OpenBookHardcover,
    playback: PlaybackSession,
    onToast: (String) -> Unit,
    onActionToast: ShowActionToast,
) {
    val detailSession = bookDetailSession(bookId, openBookDetail)
    val ratingsSession = bookRatingsSession(bookId, openBookRatings)
    // Bumped by Undo, so focus lands on the stars it puts back rather than on the page, where the
    // toast's vanished button would otherwise leave it.
    var ratingStarsFocusRequest by remember(bookId) { mutableIntStateOf(0) }
    // "Rating removed", with Undo: the one effect the rating ViewModel sends. A toast rather than a
    // dialog, because the removal has already happened and Undo is the way back.
    LaunchedEffect(ratingsSession) {
        ratingsSession.events.collect { event ->
            when (event) {
                BookRatingsEvent.RatingRemoved -> {
                    onActionToast(
                        "Rating removed",
                        ToastTone.Notice,
                        ToastAction("Undo") {
                            ratingsSession.undoClear()
                            ratingStarsFocusRequest++
                        },
                    )
                }
            }
        }
    }
    val hardcoverSession = rememberSession(bookId) { openBookHardcover(bookId) }
    // Sharing suspends (it asks the server who it is), and the press that starts it is not a
    // composition. `rememberCoroutineScope` ties the work to this page: navigate away mid-share and
    // it is cancelled rather than resolving into a toast over a book the reader has left.
    val shareScope = rememberCoroutineScope()
    LaunchedEffect(detailSession) {
        detailSession.navActions.collect { action ->
            if (action is BookDetailNavAction.BookDeleted) {
                // Leave before the tombstone syncs and the row vanishes underneath the page, and say
                // why — a page that simply disappears reads as the app losing the reader's place.
                // (Web keeps no downloads, so there is no local copy to purge as Android does.)
                val title = (detailSession.state.value as? BookDetailUiState.Ready)?.book?.title
                router.navigate(Routes.library)
                onToast(if (title != null) "“$title” was deleted." else "The book was deleted.")
            }
        }
    }
    BookDetailPage(
        state = detailSession.state.collectAsState().value,
        tab = route.query["tab"] ?: "overview",
        // replace, not navigate: panes and selection are page state, and Back should
        // leave the page rather than unwind every pane and toggle.
        onSelectTab = { tab ->
            // Animated: switching a pane is a page-level change. The selection change
            // below deliberately is not — see Router.replace.
            router.replace(Route(route.segments, route.query + ("tab" to tab)))
        },
        onOpenLibrary = { router.navigate(Routes.library) },
        bookId = bookId,
        selection = parseSelection(route.query["sel"]),
        onSelectionChange = { selection ->
            val query =
                if (selection.isEmpty()) {
                    route.query - "sel"
                } else {
                    route.query + ("sel" to selection.sorted().joinToString(","))
                }
            router.replace(Route(route.segments, query))
        },
        onPlay = { playback.onPlayBook(BookId(bookId), null) },
        isPreparing = playback.preparingBookId.collectAsState().value == bookId,
        onMarkComplete = detailSession.onMarkComplete,
        onDiscardProgress = detailSession.onDiscardProgress,
        onRestart = detailSession.onRestart,
        onShare = {
            val title = (detailSession.state.value as? BookDetailUiState.Ready)?.book?.title
            if (title != null) {
                shareScope.launch {
                    // ⛔ Only COPIED says anything. A browser that showed its own share sheet has
                    // already told the reader what happened, and a toast on top of that is the app
                    // narrating a conversation the reader just had with their own operating system.
                    when (detailSession.onShare(title)) {
                        ShareOutcome.COPIED -> onToast("Link copied")
                        ShareOutcome.FAILED -> onToast("That link could not be shared.")
                        ShareOutcome.SHARED -> Unit
                    }
                }
            }
        },
        documents = detailSession.documents.collectAsState().value,
        onRetryConnection = detailSession.onRetryConnection,
        onDeleteBook = detailSession.onDeleteBook,
        onClearDeleteError = detailSession.onClearDeleteError,
        onReleaseFromInbox = detailSession.onReleaseFromInbox,
        onRestoreToAllBooks = detailSession.onRestoreToAllBooks,
        onOpenCollection = { id -> router.navigate(Routes.Admin.collection(id)) },
        pickers = bookPickersFor(detailSession),
        onEdit = { router.navigate(Routes.Book.edit(bookId)) },
        onEditChapters = { router.navigate(Routes.Book.chapters(bookId)) },
        onMatchMetadata = { router.navigate(Routes.Book.match(bookId)) },
        onOpenGenre = { id -> router.navigate(Routes.genre(id)) },
        onOpenTag = { id -> router.navigate(Routes.tag(id)) },
        onOpenMood = { id -> router.navigate(Routes.mood(id)) },
        onOpenContributor = { id -> router.navigate(Routes.Contributor.detail(id)) },
        onOpenSeries = { id -> router.navigate(Routes.series(id)) },
        readers = bookReadersState(bookId, openBookReaders),
        ratings = ratingsSession.state.collectAsState().value,
        onRate = ratingsSession.rate,
        onClearRating = ratingsSession.clear,
        onRefreshExternalRating = ratingsSession.refreshExternal,
        onSetStars = ratingsSession.setStars,
        ratingStarsFocusRequest = ratingStarsFocusRequest,
        nowMs = nowMs(),
        onOpenProfile = { id -> router.navigate(Routes.profile(id)) },
        onSeeAllReaders = { router.navigate(Routes.Book.readers(bookId)) },
        hardcover = hardcoverSession.state.collectAsState().value,
        onFindHardcoverMatch = { router.navigate(Routes.Book.hardcover(bookId)) },
        onRemoveHardcoverMatch = hardcoverSession.onRemoveMatch,
        onSetHardcoverSynced = hardcoverSession.onSetSynced,
        lastMatchRow = { BookLastMatch(session = detailSession, nowMs = nowMs()) },
        landOnReaders = route.query[SECTION_QUERY_KEY] == READERS_SECTION,
        // Landed: the URL lets go of it, so Back to this book does not pull the page to its readers again.
        onLandedOnReaders = { router.replace(Route(route.segments, route.query - SECTION_QUERY_KEY)) },
    )
    // After Match details' Apply: what changed, See what changed and Undo, until dismissed.
    BookMatchReceipt(bookId = bookId, graph = matchDetails)
}

/** [BookPickers] over [detailSession]'s own shelf and collection picker state and actions. */
@Composable
private fun bookPickersFor(detailSession: BookDetailSession): BookPickers =
    BookPickers(
        myShelves = detailSession.myShelves.collectAsState().value,
        shelvesContainingBook = detailSession.shelvesContainingBook.collectAsState().value,
        collections = detailSession.collections.collectAsState().value,
        onShowShelfPicker = detailSession.onShowShelfPicker,
        onHideShelfPicker = detailSession.onHideShelfPicker,
        onAddToShelf = detailSession.onAddToShelf,
        onCreateShelfAndAdd = detailSession.onCreateShelfAndAdd,
        onClearShelfError = detailSession.onClearShelfError,
        onShowCollectionPicker = detailSession.onShowCollectionPicker,
        onHideCollectionPicker = detailSession.onHideCollectionPicker,
        onAddToCollection = detailSession.onAddToCollection,
        onCreateCollectionAndAdd = detailSession.onCreateCollectionAndAdd,
        onClearCollectionError = detailSession.onClearCollectionError,
    )

/**
 * Opens a readers session for [bookId] and collects it.
 */
@Composable
private fun bookReadersState(
    bookId: String,
    openBookReaders: OpenBookReaders,
): BookReadersUiState {
    val session = rememberSession(bookId) { openBookReaders(bookId) }
    return session.state.collectAsState().value
}

/** Opens a ratings session for [bookId]. */
@Composable
private fun bookRatingsSession(
    bookId: String,
    openBookRatings: OpenBookRatings,
): BookRatingsSession {
    val session = rememberSession(bookId) { openBookRatings(bookId) }
    return session
}

/** What the readers page calls the book it belongs to, before the book itself has loaded. */
private fun bookTitleOf(state: BookDetailUiState): String = (state as? BookDetailUiState.Ready)?.book?.title ?: "Book"
