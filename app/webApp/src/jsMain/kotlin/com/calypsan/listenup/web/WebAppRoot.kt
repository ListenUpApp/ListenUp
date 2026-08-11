package com.calypsan.listenup.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailUiState
import com.calypsan.listenup.web.design.WebAppSurface
import com.calypsan.listenup.web.features.bookdetail.BookDetailPage
import com.calypsan.listenup.web.features.bookdetail.OpenBookDetail
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.nav.Route
import com.calypsan.listenup.web.nav.Router
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
 */
@Composable
fun WebAppRoot(
    router: Router,
    openBookDetail: OpenBookDetail,
) {
    var collapsed by remember { mutableStateOf(false) }
    val route = router.current
    val page = route.segments.firstOrNull() ?: HOME_KEY
    // A book lives in the library, so the deep link keeps Library lit in the sidebar.
    val active = if (page == BOOK_KEY) "library" else page

    WebAppSurface {
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
                    onOpenLibrary = { router.navigate(Route(listOf("library"))) },
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
                )
            } else {
                PagePlaceholder(active)
            }
        }
    }
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

private val PRIMARY_NAV =
    NavSection(
        entries =
            listOf(
                NavEntry(HOME_KEY, "Home", WebIcon.Home),
                NavEntry("library", "Library", WebIcon.Book),
                NavEntry("discover", "Discover", WebIcon.Compass),
                NavEntry("search", "Search", WebIcon.Search),
            ),
    )

private val FOOTER_NAV =
    listOf(
        NavEntry("admin", "Admin", WebIcon.Shield),
        NavEntry("settings", "Settings", WebIcon.Cog),
    )
