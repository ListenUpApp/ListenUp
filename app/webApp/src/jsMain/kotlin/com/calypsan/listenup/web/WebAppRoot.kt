package com.calypsan.listenup.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.calypsan.listenup.web.design.WebIcon
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
        val bookId = if (page == BOOK_KEY) route.segments.getOrNull(1) else null
        if (bookId != null) {
            BookDetailPage(
                state = bookDetailState(bookId, openBookDetail),
                tab = route.query["tab"] ?: "overview",
                // replace, not navigate: panes and selection are page state, and Back should
                // leave the page rather than unwind every pane and toggle.
                onSelectTab = { tab ->
                    router.replace(Route(route.segments, route.query + ("tab" to tab)))
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
            val session = libraryState(openLibrary)
            LibraryPage(
                state = session.state.collectAsState().value,
                onEvent = session.onEvent,
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
