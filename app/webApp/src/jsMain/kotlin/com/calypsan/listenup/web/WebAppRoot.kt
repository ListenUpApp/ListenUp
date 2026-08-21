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
import com.calypsan.listenup.web.motion.withViewTransition
import com.calypsan.listenup.web.nav.Route
import com.calypsan.listenup.web.nav.Router
import com.calypsan.listenup.web.shell.AccountMenu
import com.calypsan.listenup.web.shell.NavEntry
import com.calypsan.listenup.web.shell.NavSection
import com.calypsan.listenup.web.shell.Shell
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
 */
@Composable
fun WebAppRoot(
    router: Router,
    openBookDetail: OpenBookDetail,
    openLibrary: OpenLibrary,
    openPlayback: OpenPlayback,
    onSignOut: () -> Unit = {},
) {
    var collapsed by remember { mutableStateOf(false) }
    val playback = playbackState(openPlayback)
    val route = router.current
    val page = route.segments.firstOrNull() ?: HOME_KEY
    // A book lives in the library, so the deep link keeps Library lit in the sidebar.
    val active = if (page == BOOK_KEY) LIBRARY_KEY else page

    Shell(
        sections = listOf(PRIMARY_NAV),
        active = active,
        collapsed = collapsed,
        footer = FOOTER_NAV,
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
        if (bookId != null) {
            BookDetailPage(
                state = bookDetailState(bookId, openBookDetail),
                tab = route.query["tab"] ?: "overview",
                // replace, not navigate: panes and selection are page state, and Back should
                // leave the page rather than unwind every pane and toggle.
                onSelectTab = { tab ->
                    // Animated: switching a pane is a page-level change. The selection change
                    // below deliberately is not — see Router.replace.
                    router.replace(Route(route.segments, route.query + ("tab" to tab)), animate = true)
                },
                onOpenLibrary = { router.navigate(Route(listOf(LIBRARY_KEY))) },
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
            )
        } else if (active == LIBRARY_KEY) {
            LibraryPage(
                state = animatedLibrary(librarySession),
                onEvent = librarySession.onEvent,
                onOpenBook = { id -> router.navigate(Route(listOf(BOOK_KEY, id))) },
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
 * The library list, with each change applied inside a View Transition so books that arrive, leave
 * or move do so visibly rather than by snapping.
 *
 * Renders from a mirror of the session's state rather than the flow directly, because the
 * transition has to *own* the moment the change lands — `startViewTransition` snapshots before its
 * callback and after it settles, so the write has to happen inside. Collecting straight into
 * composition would apply the change before anything could photograph the old grid.
 *
 * The first value is not animated: `shown` starts equal to it, so the identity check below skips
 * the initial load. A whole library fading in on first paint would be motion nobody asked for, and
 * `lib-card-in` already covers arrivals.
 *
 * ⛔ Scoped to `.lib-card` for a measured reason — naming all 1,204 cards took **12.2 s** to reach
 * `ready`, against **41 ms** for the ~28 in the viewport. See [withViewTransition].
 */
@Composable
private fun animatedLibrary(session: LibrarySession): LibraryUiState {
    val upstream = session.state.collectAsState().value
    var shown by remember { mutableStateOf(upstream) }
    LaunchedEffect(upstream) {
        if (shown === upstream) return@LaunchedEffect
        if (worthAnimating(shown, upstream)) {
            withViewTransition(scopeSelector = ".lib-card", prefix = "bk") { shown = upstream }
        } else {
            shown = upstream
        }
    }
    return shown
}

/**
 * Whether this list change is small enough to be worth animating.
 *
 * ⛔ **Measured, not assumed.** Against the 1,204-book library, a sort change costs **2,525 ms** of
 * recomposition on its own; wrapping it in a transition took **4,651 ms**, because the transition
 * waits for that recomposition and holds the *old* grid on screen throughout. So a wholesale
 * reorder animated is strictly worse than one that does not: twice as slow, and frozen rather than
 * progressive.
 *
 * A book arriving or leaving during sync is the opposite — a handful of cards change, the diff is
 * cheap, and the movement is the thing that was asked for. So the gate is the size of the
 * difference, not the kind of it.
 *
 * The 2.5 s reorder is a real cost that predates any of this and deserves its own fix; until then,
 * not animating it is the honest answer rather than a workaround.
 */
private fun worthAnimating(
    before: LibraryUiState,
    after: LibraryUiState,
): Boolean {
    // Only Loaded carries books; a transition out of Loading is the first paint, which
    // `lib-card-in` already owns.
    val had = (before as? LibraryUiState.Loaded)?.books ?: return false
    val has = (after as? LibraryUiState.Loaded)?.books ?: return false
    val hadIds = had.mapTo(HashSet()) { it.id.value }
    val hasIds = has.mapTo(HashSet()) { it.id.value }
    val changed = hadIds.count { it !in hasIds } + hasIds.count { it !in hadIds }
    return changed in 1..MAX_ANIMATED_CHANGE
}

/** Above this many books arriving or leaving at once, the reorder cost dominates — see [worthAnimating]. */
private const val MAX_ANIMATED_CHANGE = 24

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

private val FOOTER_NAV =
    listOf(
        NavEntry("admin", "Admin", WebIcon.Shield),
        NavEntry("settings", "Settings", WebIcon.Cog),
    )
