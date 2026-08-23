package com.calypsan.listenup.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailUiState
import com.calypsan.listenup.client.presentation.bookedit.BookEditNavAction
import com.calypsan.listenup.web.features.bookedit.BookEditPage
import com.calypsan.listenup.web.features.bookedit.BookEditSession
import com.calypsan.listenup.web.features.bookedit.OpenBookEdit
import com.calypsan.listenup.web.features.bookdetail.BookDetailPage
import com.calypsan.listenup.web.features.bookdetail.OpenBookDetail
import com.calypsan.listenup.web.features.library.LibraryPage
import com.calypsan.listenup.web.features.library.LibrarySession
import com.calypsan.listenup.web.features.library.OpenLibrary
import com.calypsan.listenup.web.features.nowplaying.OpenPlayback
import com.calypsan.listenup.web.features.nowplaying.PlaybackNotice
import com.calypsan.listenup.web.features.nowplaying.PlaybackSession
import com.calypsan.listenup.web.features.nowplaying.TransportBar
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.presentation.library.LibraryUiState
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.nav.Route
import com.calypsan.listenup.web.nav.Router
import com.calypsan.listenup.web.shell.AccountMenu
import com.calypsan.listenup.web.shell.NavEntry
import com.calypsan.listenup.web.shell.NavSection
import com.calypsan.listenup.web.shell.Shell
import kotlinx.coroutines.flow.Flow
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Text

/**
 * The root of the ListenUp web body: the Shell A chrome around a router-driven content region.
 *
 * The sidebar drives the URL and the URL drives the sidebar — the active item is *derived* from
 * [Router.current] rather than stored, so a deep link, a Back press and a sidebar click all agree
 * by construction.
 *
 * [com.calypsan.listenup.web.design.WebAppSurface] is applied by
 * [com.calypsan.listenup.web.features.auth.AuthGate], not here — every auth branch needs it too,
 * and applying it in both places would nest `.luw` inside `.luw`.
 *
 * [openPlayback] is scoped to the shell rather than to a route: what is playing outlives the page
 * the listener happened to start it from. It has no default on purpose — see [fixedPlayback], which
 * is what a spec passes instead.
 *
 * [observeIsAdmin] gates the sidebar's Admin entry. No default for the same reason [openPlayback]
 * has none: a defaulted `flowOf(false)` would compile clean while silently hiding Admin from every
 * admin, and nothing would look broken from the outside.
 */
@Composable
fun WebAppRoot(
    router: Router,
    openBookDetail: OpenBookDetail,
    openBookEdit: OpenBookEdit,
    openLibrary: OpenLibrary,
    openPlayback: OpenPlayback,
    observeIsAdmin: () -> Flow<Boolean>,
    onSignOut: () -> Unit = {},
) {
    var collapsed by remember { mutableStateOf(false) }
    // Starts false so a member never sees the entry flash; an admin's entry appears the moment
    // the repository's flow answers — the same read Book Edit gates its Collections field on.
    val isAdmin by remember { observeIsAdmin() }.collectAsState(initial = false)

    // Which grid tile is the shared element. Set on the way into a book and kept afterwards, so the
    // flight works in both directions: out to the detail hero, and back to the same tile on return.
    // The library's scrollport is the shell's, which does not unmount on a route change, so coming
    // back lands at the same offset and the tile is usually still on screen. When it is not — the
    // grid is virtualised — there is simply nothing to fly to and the pages crossfade instead.
    var heroBookId by remember { mutableStateOf<String?>(null) }
    val playback = playbackState(openPlayback)
    val route = router.current
    val page = route.segments.firstOrNull() ?: HOME_KEY
    // A book lives in the library, so the deep link keeps Library lit in the sidebar.
    val active = if (page == BOOK_KEY) LIBRARY_KEY else page

    Shell(
        sections = listOf(PRIMARY_NAV),
        active = active,
        collapsed = collapsed,
        footer = if (isAdmin) FOOTER_NAV else FOOTER_NAV.filterNot { it.key == ADMIN_KEY },
        onToggleCollapse = { collapsed = !collapsed },
        onNavigate = { key ->
            val segments = if (key == HOME_KEY) emptyList() else listOf(key)
            router.navigate(Route(segments))
        },
    ) {
        AccountMenu(onSignOut = onSignOut)
        // Opened once for the shell's lifetime rather than per visit. Closing it on the way to a
        // book meant coming back rebuilt the ViewModel and re-queried all 1,204 rows — measured at
        // **478 ms** of "Loading…" every single time, for a list the reader had just been looking
        // at. A Room-backed flow costs almost nothing to keep subscribed, and keeping it is what
        // makes going back instant instead of merely fast.
        val librarySession = libraryState(openLibrary)
        val bookId = if (page == BOOK_KEY) route.segments.getOrNull(1) else null
        // `/book/{id}/edit` — a route of its own rather than a mode of Book Detail, so the form is
        // linkable, Back leaves it, and a half-finished edit cannot be mistaken for the book.
        val editingBookId = if (bookId != null && route.segments.getOrNull(2) == EDIT_KEY) bookId else null
        if (editingBookId != null) {
            val editSession =
                bookEditState(
                    bookId = editingBookId,
                    openBookEdit = openBookEdit,
                    onLeave = { router.navigate(Route(listOf(BOOK_KEY, editingBookId))) },
                )
            BookEditPage(
                state = editSession.state.collectAsState().value,
                onEvent = editSession.onEvent,
                onOpenLibrary = { router.navigate(Route(listOf(LIBRARY_KEY))) },
                onOpenBook = { router.navigate(Route(listOf(BOOK_KEY, editingBookId))) },
            )
        } else if (bookId != null) {
            BookDetailPage(
                state = bookDetailState(bookId, openBookDetail),
                tab = route.query["tab"] ?: "overview",
                // replace, not navigate: panes and selection are page state, and Back should
                // leave the page rather than unwind every pane and toggle.
                onSelectTab = { tab ->
                    // Animated: switching a pane is a page-level change. The selection change
                    // below deliberately is not — see Router.replace.
                    router.replace(Route(route.segments, route.query + ("tab" to tab)))
                },
                onOpenLibrary = { router.navigate(Route(listOf(LIBRARY_KEY))) },
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
                onPlay = { playback.onPlayBook(BookId(bookId)) },
                onEdit = { router.navigate(Route(listOf(BOOK_KEY, bookId, EDIT_KEY))) },
            )
        } else if (active == LIBRARY_KEY) {
            LibraryPage(
                state = animatedLibrary(librarySession),
                onEvent = librarySession.onEvent,
                onOpenBook = { id ->
                    heroBookId = id
                    router.navigate(Route(listOf(BOOK_KEY, id)))
                },
                heroBookId = heroBookId,
            )
        } else {
            PagePlaceholder(active)
        }

        // Both last inside the content region, so they sit under whatever page is showing and
        // stay put as the reader moves between them. The notice comes first because it is often
        // the only one of the two rendering: the failures it reports are exactly the ones that
        // leave nothing playing, and therefore no bar.
        PlaybackNotice(
            message = playback.error.collectAsState().value,
            onDismiss = playback.onDismissError,
        )
        TransportBar(
            state = playback.state.collectAsState().value,
            onPlayPause = playback.onPlayPause,
            onSeek = playback.onSeek,
        )
    }
}

/**
 * Opens the playback session for as long as the shell is mounted, and closes it when it is not.
 *
 * `remember` with no key on purpose: this session is the shell's, not a route's, so a navigation
 * must not tear the player down mid-sentence.
 */
@Composable
private fun playbackState(openPlayback: OpenPlayback): PlaybackSession {
    val session = remember { openPlayback() }
    DisposableEffect(session) { onDispose { session.close() } }
    return session
}

/**
 * Opens a Book Detail session for [bookId] and collects it, closing the previous one whenever the
 * book changes or the page goes away. Keyed on [bookId] so navigating between books tears the old
 * ViewModel down instead of stacking another live one behind it.
 */
@Composable
private fun bookDetailState(
    bookId: String,
    openBookDetail: OpenBookDetail,
): BookDetailUiState {
    val session = remember(bookId) { openBookDetail(bookId) }
    DisposableEffect(session) { onDispose { session.close() } }
    return session.state.collectAsState().value
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
    val session = remember(bookId) { openBookEdit(bookId) }
    DisposableEffect(session) { onDispose { session.close() } }
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
 * Opens a Library session while the page is showing and closes it when it stops, so the ViewModel's
 * flows do not outlive the route.
 */
@Composable
private fun libraryState(openLibrary: OpenLibrary): LibrarySession {
    val session = remember { openLibrary() }
    DisposableEffect(session) { onDispose { session.close() } }
    return session
}

/**
 * The library list, mirrored into local state.
 *
 * ⛔ **This used to wrap each change in a View Transition, and that has been removed** — not tuned,
 * removed. The browser's shared-element API needs the DOM change to happen inside its update
 * callback, and **Compose HTML cannot render in there**: the browser suppresses rendering while the
 * callback is outstanding, and Compose's scheduler needs a frame. Measured, the destination had
 * still not rendered **361 ms** into the callback, so every transition captured an identical before
 * and after — it animated nothing while holding the old page frozen for the settle, which is what
 * read as a lurch and a flash on every navigation.
 *
 * The cover's flight between the grid and the book survives, because it does not depend on that
 * callback at all — see `HeroFlight`.
 */
@Composable
private fun animatedLibrary(session: LibrarySession): LibraryUiState {
    val upstream = session.state.collectAsState().value
    var shown by remember { mutableStateOf(upstream) }
    LaunchedEffect(upstream) { shown = upstream }
    return shown
}

/**
 * Stands in for the pages that arrive next (Book Detail first). Honest about being unbuilt
 * rather than mocked up — a placeholder that looks real is a bug report waiting to happen.
 */
@Composable
private fun PagePlaceholder(key: String) {
    val label = (PRIMARY_NAV.entries + FOOTER_NAV).firstOrNull { it.key == key }?.label ?: key
    Div(attrs = { classes("empty") }) {
        H3 { Text(label) }
        P { Text("This page is not built yet.") }
    }
}

/** `sel=9,10` → the selected chapter numbers; junk entries are dropped rather than crashing. */
private fun parseSelection(raw: String?): Set<Int> =
    raw
        .orEmpty()
        .split(',')
        .mapNotNull { it.trim().toIntOrNull() }
        .toSet()

private const val HOME_KEY = "home"

private const val BOOK_KEY = "book"

/** The trailing segment that turns a book route into its edit form. */
private const val EDIT_KEY = "edit"

private const val LIBRARY_KEY = "library"

private val PRIMARY_NAV =
    NavSection(
        entries =
            listOf(
                NavEntry(HOME_KEY, "Home", WebIcon.Home),
                NavEntry(LIBRARY_KEY, "Library", WebIcon.Book),
                NavEntry("discover", "Discover", WebIcon.Compass),
                NavEntry("search", "Search", WebIcon.Search),
            ),
    )

private const val ADMIN_KEY = "admin"

private val FOOTER_NAV =
    listOf(
        NavEntry(ADMIN_KEY, "Admin", WebIcon.Shield),
        NavEntry("settings", "Settings", WebIcon.Cog),
    )
