package com.calypsan.listenup.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.domain.model.ContributorRole
import com.calypsan.listenup.client.domain.model.SearchHit
import com.calypsan.listenup.client.domain.model.SearchHitType
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailUiState
import com.calypsan.listenup.client.presentation.bookedit.BookEditNavAction
import com.calypsan.listenup.client.presentation.contributordetail.ContributorDetailUiState
import com.calypsan.listenup.client.presentation.seriesdetail.SeriesDetailUiState
import com.calypsan.listenup.client.presentation.search.SearchNavAction
import com.calypsan.listenup.client.presentation.search.SearchUiState
import com.calypsan.listenup.web.features.bookedit.BookEditPage
import com.calypsan.listenup.web.features.bookedit.BookEditSession
import com.calypsan.listenup.web.features.bookedit.OpenBookEdit
import com.calypsan.listenup.web.features.bookdetail.BookDetailPage
import com.calypsan.listenup.web.features.bookdetail.OpenBookDetail
import com.calypsan.listenup.web.features.contributordetail.ContributorDetailPage
import com.calypsan.listenup.web.features.contributordetail.OpenContributorDetail
import com.calypsan.listenup.web.features.contributors.ContributorsPage
import com.calypsan.listenup.web.features.contributors.ContributorsSession
import com.calypsan.listenup.web.features.contributors.OpenContributors
import com.calypsan.listenup.web.features.library.LibraryPage
import com.calypsan.listenup.web.features.library.LibrarySession
import com.calypsan.listenup.web.features.library.OpenLibrary
import com.calypsan.listenup.web.features.nowplaying.OpenPlayback
import com.calypsan.listenup.web.features.nowplaying.PlaybackNotice
import com.calypsan.listenup.web.features.nowplaying.PlaybackSession
import com.calypsan.listenup.web.features.nowplaying.TransportBar
import com.calypsan.listenup.web.features.home.HomePage
import com.calypsan.listenup.web.features.discover.DiscoverPage
import com.calypsan.listenup.web.features.discover.OpenDiscover
import com.calypsan.listenup.web.features.home.OpenHome
import com.calypsan.listenup.web.features.admin.AdminPage
import com.calypsan.listenup.web.features.admin.OpenAdmin
import com.calypsan.listenup.web.features.devices.DevicesPage
import com.calypsan.listenup.web.features.devices.OpenDevices
import com.calypsan.listenup.web.features.settings.OpenSettings
import com.calypsan.listenup.web.features.settings.SettingsPage
import com.calypsan.listenup.web.features.shelf.OpenShelfDetail
import com.calypsan.listenup.web.features.shelf.OpenShelfEdit
import com.calypsan.listenup.web.features.shelf.ShelfDetailPage
import com.calypsan.listenup.web.features.shelf.ShelfEditPage
import com.calypsan.listenup.web.features.shelf.ShelfRoute
import com.calypsan.listenup.web.features.shelf.shelfRouteOf
import com.calypsan.listenup.web.features.search.CommandPalette
import com.calypsan.listenup.web.features.search.OpenSearch
import com.calypsan.listenup.web.features.search.SearchPage
import com.calypsan.listenup.web.features.search.SearchSession
import com.calypsan.listenup.web.features.search.openableSearchHits
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.currentEpochMilliseconds
import com.calypsan.listenup.client.presentation.library.LibraryUiState
import com.calypsan.listenup.web.design.LibraryFacet
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.features.seriesdetail.OpenSeriesDetail
import com.calypsan.listenup.web.features.seriesdetail.SeriesDetailPage
import com.calypsan.listenup.web.nav.Route
import com.calypsan.listenup.web.nav.Router
import com.calypsan.listenup.web.shell.AccountMenu
import com.calypsan.listenup.web.shell.NavEntry
import com.calypsan.listenup.web.shell.NavSection
import com.calypsan.listenup.web.shell.Shell
import com.calypsan.listenup.web.motion.fadePageIn
import com.calypsan.listenup.web.motion.isPageChange
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.flow.Flow
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent

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
 *
 * [openSearch] has no default for the same reason: a defaulted fake would compile clean and leave
 * the sidebar's Search item permanently landing on a page that never does anything. [openHome] is
 * the same story for the root route.
 */
@Composable
fun WebAppRoot(
    router: Router,
    openBookDetail: OpenBookDetail,
    openBookEdit: OpenBookEdit,
    openContributorDetail: OpenContributorDetail,
    openSeriesDetail: OpenSeriesDetail,
    openContributors: OpenContributors,
    openHome: OpenHome,
    openDiscover: OpenDiscover,
    openSettings: OpenSettings,
    openDevices: OpenDevices,
    openAdmin: OpenAdmin,
    openShelfDetail: OpenShelfDetail,
    openShelfEdit: OpenShelfEdit,
    openLibrary: OpenLibrary,
    openSearch: OpenSearch,
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
    // A book, the person behind it, or the series it belongs to all live in the library, so
    // every one of those deep links keeps Library lit in the sidebar.
    val active = if (page in LIBRARY_DEEP_LINKS) LIBRARY_KEY else page

    // A page change fades; a route change within one does not. `lastPage` starts null so the first
    // paint is not a fade — a library materialising out of nothing on load is motion nobody asked
    // for, and it would sit between the reader and content that has already arrived.
    var lastPage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(page) {
        if (isPageChange(lastPage, page)) {
            document.querySelector(SHELL_MAIN)?.let { fadePageIn(it) }
        }
        lastPage = page
    }

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
        RouteContent(
            router = router,
            route = route,
            page = page,
            active = active,
            openBookDetail = openBookDetail,
            openBookEdit = openBookEdit,
            openContributorDetail = openContributorDetail,
            openSeriesDetail = openSeriesDetail,
            openContributors = openContributors,
            openHome = openHome,
            openDiscover = openDiscover,
            openSettings = openSettings,
            openDevices = openDevices,
            openAdmin = openAdmin,
            openShelfDetail = openShelfDetail,
            openShelfEdit = openShelfEdit,
            openSearch = openSearch,
            librarySession = librarySession,
            playback = playback,
            heroBookId = heroBookId,
            onHeroBookIdChange = { heroBookId = it },
        )

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
            onSkipBack = playback.onSkipBack,
            onSkipForward = playback.onSkipForward,
            onSetSpeed = playback.onSetSpeed,
            onResetSpeed = playback.onResetSpeed,
            defaultSpeed = playback.defaultSpeed.collectAsState().value,
            chapters = playback.chapters.collectAsState().value,
            currentChapterIndex = playback.currentChapterIndex.collectAsState().value,
            onSeekToChapter = playback.onSeekToChapter,
            sleepTimer = playback.sleepTimer.collectAsState().value,
            onSetSleepTimer = playback.onSetSleepTimer,
            onCancelSleepTimer = playback.onCancelSleepTimer,
            onExtendSleepTimer = playback.onExtendSleepTimer,
        )

        // Last of all: the palette overlays everything above it, including the transport bar.
        CommandPaletteHost(router = router, openSearch = openSearch)
    }
}

/**
 * The one page the current route actually shows — Book Edit, Book Detail, Contributors, Library
 * or the placeholder — pulled out of [WebAppRoot] itself purely to keep that function's branching
 * readable as a single glance rather than one long `if`/`else if` chain.
 */
@Composable
private fun RouteContent(
    router: Router,
    route: Route,
    page: String,
    active: String,
    openBookDetail: OpenBookDetail,
    openBookEdit: OpenBookEdit,
    openContributorDetail: OpenContributorDetail,
    openSeriesDetail: OpenSeriesDetail,
    openContributors: OpenContributors,
    openHome: OpenHome,
    openDiscover: OpenDiscover,
    openSettings: OpenSettings,
    openDevices: OpenDevices,
    openAdmin: OpenAdmin,
    openShelfDetail: OpenShelfDetail,
    openShelfEdit: OpenShelfEdit,
    openSearch: OpenSearch,
    librarySession: LibrarySession,
    playback: PlaybackSession,
    heroBookId: String?,
    onHeroBookIdChange: (String) -> Unit,
) {
    val shelfRoute = shelfRouteOf(route.segments)
    val bookId = if (page == BOOK_KEY) route.segments.getOrNull(1) else null
    // `/book/{id}/edit` — a route of its own rather than a mode of Book Detail, so the form is
    // linkable, Back leaves it, and a half-finished edit cannot be mistaken for the book.
    val editingBookId = if (bookId != null && route.segments.getOrNull(2) == EDIT_KEY) bookId else null
    // `/library/contributors` — the second segment turns the Library route into the people
    // behind it, rather than a route of its own, so the sidebar stays lit on Library either way.
    val isContributors = active == LIBRARY_KEY && route.segments.getOrNull(1) == CONTRIBUTORS_KEY
    // `/contributor/{id}` — the person behind the books, a route of its own (unlike the list, one
    // book's worth of detail is not a facet of anything else).
    val contributorId = if (page == CONTRIBUTOR_KEY) route.segments.getOrNull(1) else null
    // `/series/{id}` — a route of its own for the same reason a contributor's page is one: a
    // series is something you arrive at and link to, not a filter over the library grid.
    val seriesId = if (page == SERIES_KEY) route.segments.getOrNull(1) else null

    if (bookId != null) {
        BookRouteContent(
            bookId = bookId,
            editingBookId = editingBookId,
            router = router,
            route = route,
            openBookDetail = openBookDetail,
            openBookEdit = openBookEdit,
            playback = playback,
        )
    } else if (isContributors) {
        val role = parseContributorRole(route.query[ROLE_QUERY_KEY])
        val contributorsSession = contributorsState(role, openContributors)
        ContributorsPage(
            state = contributorsSession.state.collectAsState().value,
            role = role,
            onSelectFacet = { facet -> router.navigate(routeFor(facet)) },
            onOpenContributor = { id -> router.navigate(Route(listOf(CONTRIBUTOR_KEY, id))) },
        )
    } else if (contributorId != null) {
        ContributorDetailPage(
            state = contributorDetailState(contributorId, openContributorDetail),
            onOpenLibrary = { router.navigate(Route(listOf(LIBRARY_KEY))) },
            onOpenContributors = { router.navigate(Route(listOf(LIBRARY_KEY, CONTRIBUTORS_KEY))) },
            onOpenBook = { id -> router.navigate(Route(listOf(BOOK_KEY, id))) },
            onOpenSeries = { id -> router.navigate(Route(listOf(SERIES_KEY, id))) },
        )
    } else if (seriesId != null) {
        SeriesDetailPage(
            state = seriesDetailState(seriesId, openSeriesDetail),
            onOpenLibrary = { router.navigate(Route(listOf(LIBRARY_KEY))) },
            onOpenBook = { id -> router.navigate(Route(listOf(BOOK_KEY, id))) },
            onPlayBook = { id -> playback.onPlayBook(BookId(id)) },
        )
    } else if (active == LIBRARY_KEY) {
        LibraryPage(
            state = animatedLibrary(librarySession),
            onEvent = librarySession.onEvent,
            onOpenBook = { id ->
                onHeroBookIdChange(id)
                router.navigate(Route(listOf(BOOK_KEY, id)))
            },
            onSelectFacet = { facet -> router.navigate(routeFor(facet)) },
            heroBookId = heroBookId,
        )
    } else if (page == SEARCH_KEY) {
        SearchRoute(router = router, route = route, openSearch = openSearch)
    } else if (active == HOME_KEY) {
        HomeRoute(router = router, openHome = openHome, onHeroBookIdChange = onHeroBookIdChange)
    } else if (shelfRoute != null) {
        ShelfRouteContent(
            shelfRoute = shelfRoute,
            router = router,
            openShelfDetail = openShelfDetail,
            openShelfEdit = openShelfEdit,
            onHeroBookIdChange = onHeroBookIdChange,
        )
    } else if (isAccountRoute(route.segments, active)) {
        AccountRouteContent(
            segments = route.segments,
            active = active,
            router = router,
            openSettings = openSettings,
            openDevices = openDevices,
            openAdmin = openAdmin,
        )
    } else if (active == DISCOVER_KEY) {
        DiscoverRoute(router = router, openDiscover = openDiscover, onHeroBookIdChange = onHeroBookIdChange)
    } else {
        PagePlaceholder(active)
    }
}

/**
 * The `/` branch of [RouteContent] — Home's session lifecycle and its three destinations.
 *
 * The session opens when Home starts showing and closes when it stops, the same arrangement
 * [SearchRoute] makes: a browser has no `ViewModelStore` to hand two ViewModels' lifetimes to.
 * Unlike the library's, this session is NOT held for the shell's lifetime — Home's upstreams are
 * cheap to resubscribe, and holding the stats ViewModel open would keep its per-minute midnight
 * ticker running behind every other page.
 *
 * Opening a book from here records the hero origin first, so the cover flies into the detail page
 * exactly as it does from the library grid.
 */
@Composable
private fun HomeRoute(
    router: Router,
    openHome: OpenHome,
    onHeroBookIdChange: (String) -> Unit,
) {
    val session = remember { openHome() }
    DisposableEffect(session) { onDispose { session.close() } }

    HomePage(
        state = session.state.collectAsState().value,
        stats = session.stats.collectAsState().value,
        onOpenBook = { id ->
            onHeroBookIdChange(id)
            router.navigate(Route(listOf(BOOK_KEY, id)))
        },
        onOpenSearch = { router.navigate(Route(listOf(SEARCH_KEY))) },
        onOpenLibrary = { router.navigate(Route(listOf(LIBRARY_KEY))) },
        onOpenShelf = { id -> router.navigate(Route(listOf(SHELF_KEY, id))) },
        onCreateShelf = { router.navigate(Route(listOf(SHELF_KEY, NEW_KEY))) },
    )
}

/**
 * The `/discover` branch — Discover's session lifecycle and its one destination.
 *
 * The session opens when Discover starts showing and closes when it stops, the arrangement
 * [HomeRoute] makes for the same reason: a browser has no `ViewModelStore` to hand three
 * ViewModels' lifetimes to. Not held for the shell's lifetime — Discover's upstreams are cheap to
 * resubscribe, and its leaderboard would otherwise keep querying behind every other page.
 *
 * `nowMs` is read once per visit rather than ticking. Every relative time on this page is at least
 * a minute old ("3 minutes ago", "2 days ago"), so a ticking clock would buy a correction almost
 * nobody is on screen long enough to see, at the cost of re-rendering the whole feed each second.
 */
@Composable
private fun DiscoverRoute(
    router: Router,
    openDiscover: OpenDiscover,
    onHeroBookIdChange: (String) -> Unit,
) {
    val session = remember { openDiscover() }
    DisposableEffect(session) { onDispose { session.close() } }
    val nowMs = remember { currentEpochMilliseconds() }

    DiscoverPage(
        books = session.books.collectAsState().value,
        recentlyAdded = session.recentlyAdded.collectAsState().value,
        currentlyListening = session.currentlyListening.collectAsState().value,
        leaderboard = session.leaderboard.collectAsState().value,
        activity = session.activity.collectAsState().value,
        shelves = session.shelves.collectAsState().value,
        nowMs = nowMs,
        onOpenBook = { id ->
            onHeroBookIdChange(id)
            router.navigate(Route(listOf(BOOK_KEY, id)))
        },
        onOpenShelf = { id -> router.navigate(Route(listOf(SHELF_KEY, id))) },
        onSelectPeriod = session.onSelectPeriod,
        onSelectCategory = session.onSelectCategory,
    )
}

/**
 * The `/search` branch of [RouteContent] — session lifecycle, the two-way `?q=` binding with the
 * address bar, and hit navigation. Extracted so [RouteContent] stays a single glance of routing
 * rather than a second page's worth of effects folded into one branch.
 *
 * The `?q=` binding runs in both directions. URL → session: a shared `/search?q=dune` link (or a
 * Back/Forward press) seeds the field, via a [LaunchedEffect] keyed on the URL's own `q`. Session →
 * URL: typing calls [Router.replace] synchronously from the same callback that tells the session
 * about the change — not a second effect watching [SearchSession.state] — so the URL never lags a
 * keystroke behind. Either way it's [Router.replace], never [Router.navigate]: one history entry
 * for the whole visit, not one per keystroke, so Back leaves the search rather than replaying it
 * letter by letter.
 *
 * Books and contributors have somewhere to go; Series and Tag detail routes don't exist yet.
 * [SearchPage] is told exactly which hit types are openable ([SEARCH_OPENABLE_TYPES]) so the rest
 * render their data honestly inert rather than as a click that silently does nothing.
 */
@Composable
private fun SearchRoute(
    router: Router,
    route: Route,
    openSearch: OpenSearch,
) {
    val session = remember { openSearch() }
    DisposableEffect(session) { onDispose { session.close() } }

    // URL -> session: seed (and re-seed on Back/Forward) the query the address bar names.
    LaunchedEffect(session, route.query[SEARCH_QUERY_KEY]) {
        val urlQuery = route.query[SEARCH_QUERY_KEY].orEmpty()
        if (urlQuery != session.state.value.query) session.onQueryChanged(urlQuery)
    }

    LaunchedEffect(session) {
        session.navActions.collect { action ->
            when (action) {
                is SearchNavAction.NavigateToBook -> {
                    router.navigate(Route(listOf(BOOK_KEY, action.bookId)))
                }

                is SearchNavAction.NavigateToContributor -> {
                    router.navigate(Route(listOf(CONTRIBUTOR_KEY, action.contributorId)))
                }

                // No destination yet. SEARCH_OPENABLE_TYPES keeps these hit types' rows
                // non-interactive, so a click never reaches here in practice.
                is SearchNavAction.NavigateToSeries,
                is SearchNavAction.NavigateToTag,
                -> {
                    Unit
                }
            }
        }
    }

    SearchPage(
        state = session.state.collectAsState().value,
        onQueryChanged = { query -> onSearchFieldChanged(query, session, router) },
        onToggleType = session.onToggleType,
        onOpenHit = session.onOpenHit,
        onRetry = session.retry,
        openableTypes = SEARCH_OPENABLE_TYPES,
    )
}

/** session -> URL, the other half of [SearchRoute]'s two-way `?q=` binding — see its KDoc. */
private fun onSearchFieldChanged(
    query: String,
    session: SearchSession,
    router: Router,
) {
    session.onQueryChanged(query)
    val urlQuery = router.current.query[SEARCH_QUERY_KEY].orEmpty()
    if (urlQuery == query) return
    val newQuery =
        if (query.isBlank()) {
            router.current.query - SEARCH_QUERY_KEY
        } else {
            router.current.query +
                (SEARCH_QUERY_KEY to query)
        }
    router.replace(Route(router.current.segments, newQuery))
}

/**
 * The ⌘K / Ctrl+K / `/` command palette's lifecycle, wiring and single keyboard listener.
 *
 * Mounted once at the shell's root, like [playbackState], so the shortcut works from every route
 * — not just `/search`. Opens its OWN [SearchSession] on each open and closes it on each close,
 * never the one [SearchRoute] holds: the palette is a transient jump tool, and sharing a session
 * would mean either leaking one across every open/close cycle a page never tore down, or fighting
 * the `/search` route over whose query wins. A fresh session every time also means the palette
 * always starts at [SearchUiState.Idle] — the same "start clean" contract [bookDetailState] gives
 * a fresh book visit.
 *
 * All of this palette's keyboard behaviour — opening, arrow navigation, Enter, Shift+Enter, Escape
 * and the focus trap — lives in ONE `window`-level `keydown` listener, registered for the shell's
 * whole lifetime rather than only while the palette is open. That is what lets `/` and ⌘K work
 * from anywhere: a listener scoped to the palette's own composition cannot hear a keystroke fired
 * before the palette exists. Routing every in-palette key through that same listener — rather than
 * a second one attached once the panel mounts — is also what makes the "Tab cannot escape" trap
 * trivial to get right: Tab is simply intercepted and dropped for as long as [isOpen][Boolean] is
 * true, so focus never has anywhere else to go, regardless of what element happens to hold it.
 *
 * Focus discipline: the moment the shortcut fires, `document.activeElement` is captured into
 * `restoreFocusTo` — before [CommandPalette] mounts and steals it — so closing can hand focus back
 * to the exact control the reader was on, on every close path alike (Escape, a book hit, or the
 * Shift+Enter commit).
 */
@Composable
private fun CommandPaletteHost(
    router: Router,
    openSearch: OpenSearch,
) {
    var isOpen by remember { mutableStateOf(false) }
    var session by remember { mutableStateOf<SearchSession?>(null) }
    var highlighted by remember { mutableStateOf<SearchHit?>(null) }
    var restoreFocusTo by remember { mutableStateOf<HTMLElement?>(null) }

    fun closePalette() {
        isOpen = false
        session?.close()
        session = null
        highlighted = null
        restoreFocusTo?.focus()
        restoreFocusTo = null
    }

    fun openPalette() {
        restoreFocusTo = document.activeElement as? HTMLElement
        val newSession = openSearch()
        session = newSession
        // Computed synchronously from the fresh session's own StateFlow.value, not left for the
        // LaunchedEffect below to fill in — that effect only runs on the composition's next pass,
        // one recompose later than this. A session opened via [fixedSearch]/[hitNavigatingSearch]
        // already carries its Results on open, so waiting for the effect meant the very first
        // frame rendered with no row highlighted at all.
        highlighted = openableSearchHits(newSession.state.value, SEARCH_OPENABLE_TYPES).firstOrNull()
        isOpen = true
    }

    fun moveHighlight(delta: Int) {
        val hits = openableSearchHits(session?.state?.value ?: return, SEARCH_OPENABLE_TYPES)
        if (hits.isEmpty()) {
            highlighted = null
            return
        }
        val currentIndex = hits.indexOf(highlighted).let { if (it < 0) 0 else it }
        highlighted = hits[(currentIndex + delta).mod(hits.size)]
    }

    DisposableEffect(Unit) {
        val onWindowKeyDown =
            paletteKeyDownHandler(
                isOpen = { isOpen },
                onOpen = ::openPalette,
                onClose = ::closePalette,
                onMoveHighlight = ::moveHighlight,
                onActivateHighlighted = {
                    session?.let { activeSession -> highlighted?.let(activeSession.onOpenHit) }
                },
                onCommit = {
                    session?.let { activeSession ->
                        val query = activeSession.state.value.query
                        closePalette()
                        router.navigate(paletteSearchRoute(query))
                    }
                },
            )
        window.addEventListener("keydown", onWindowKeyDown)
        onDispose { window.removeEventListener("keydown", onWindowKeyDown) }
    }

    val activeSession = session
    if (isOpen && activeSession != null) {
        LaunchedEffect(activeSession) {
            activeSession.navActions.collect { action -> handlePaletteNavAction(action, router, ::closePalette) }
        }

        val uiState = activeSession.state.collectAsState().value
        // Keeps the highlight valid as results arrive: resets to the first openable hit whenever
        // the candidate list changes, so a stale highlight from the previous query never survives
        // into a new result set, and clears it when nothing is openable at all.
        LaunchedEffect(activeSession, uiState) {
            val hits = openableSearchHits(uiState, SEARCH_OPENABLE_TYPES)
            if (highlighted !in hits) highlighted = hits.firstOrNull()
        }

        CommandPalette(
            state = uiState,
            onQueryChanged = activeSession.onQueryChanged,
            onOpenHit = activeSession.onOpenHit,
            openableTypes = SEARCH_OPENABLE_TYPES,
            highlighted = highlighted,
        )
    }
}

/**
 * Builds [CommandPaletteHost]'s single `window`-level `keydown` handler from the callbacks it
 * needs invoked. Pulled out of the composable itself so its own branching doesn't count against
 * [CommandPaletteHost]'s cognitive/cyclomatic complexity — this is a plain dispatch table, not
 * state the composable needs to reason about.
 */
private fun paletteKeyDownHandler(
    isOpen: () -> Boolean,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    onMoveHighlight: (Int) -> Unit,
    onActivateHighlighted: () -> Unit,
    onCommit: () -> Unit,
): (Event) -> Unit =
    handler@{ event ->
        val keyboardEvent = event as KeyboardEvent
        if (!isOpen()) {
            val isShortcut =
                keyboardEvent.key.equals(PALETTE_SHORTCUT_KEY, ignoreCase = true) &&
                    (keyboardEvent.metaKey || keyboardEvent.ctrlKey)
            val isSlash = keyboardEvent.key == "/" && !isEditableTarget(document.activeElement)
            if (!isShortcut && !isSlash) return@handler
            keyboardEvent.preventDefault()
            onOpen()
            return@handler
        }
        when (keyboardEvent.key) {
            "Escape" -> {
                keyboardEvent.preventDefault()
                onClose()
            }

            // The whole focus trap: with nowhere else in the palette to tab to, Tab simply does
            // nothing while it's open.
            "Tab" -> {
                keyboardEvent.preventDefault()
            }

            "ArrowDown" -> {
                keyboardEvent.preventDefault()
                onMoveHighlight(1)
            }

            "ArrowUp" -> {
                keyboardEvent.preventDefault()
                onMoveHighlight(-1)
            }

            "Enter" -> {
                keyboardEvent.preventDefault()
                if (keyboardEvent.shiftKey) onCommit() else onActivateHighlighted()
            }
        }
    }

/**
 * The palette's half of [SearchRoute]'s own nav-action handling — only [SearchNavAction.NavigateToBook]
 * has a destination on this branch yet, same reasoning as there. Pulled out for the same
 * complexity-budget reason as [paletteKeyDownHandler].
 */
private fun handlePaletteNavAction(
    action: SearchNavAction,
    router: Router,
    closePalette: () -> Unit,
) {
    when (action) {
        is SearchNavAction.NavigateToBook -> {
            closePalette()
            router.navigate(Route(listOf(BOOK_KEY, action.bookId)))
        }

        // No destination on this branch yet. openableSearchHits keeps these types unreachable by
        // keyboard, and their rows render inert, so this path is only ever exercised in theory.
        is SearchNavAction.NavigateToContributor,
        is SearchNavAction.NavigateToSeries,
        is SearchNavAction.NavigateToTag,
        -> {
            Unit
        }
    }
}

/** Where the palette's Shift+Enter commits: `/search`, or `/search?q=…` for a non-blank query. */
private fun paletteSearchRoute(query: String): Route =
    if (query.isBlank()) Route(listOf(SEARCH_KEY)) else Route(listOf(SEARCH_KEY), mapOf(SEARCH_QUERY_KEY to query))

/**
 * True for a text input, a textarea, or a `contenteditable` region — everywhere `/` must type the
 * character rather than open the palette out from under the reader.
 */
private fun isEditableTarget(target: Element?): Boolean {
    val element = target as? HTMLElement ?: return false
    return element.isContentEditable || element.tagName == "INPUT" || element.tagName == "TEXTAREA"
}

/** The letter half of the ⌘K / Ctrl+K shortcut. */
private const val PALETTE_SHORTCUT_KEY = "k"

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
 * An open Contributors session for [role], closed whenever the role changes or the page goes away.
 *
 * Keyed on [role] rather than a bare `remember { }` — the same reason [bookDetailState] keys on
 * `bookId` — so switching from Authors to Narrators tears the old session down instead of
 * rendering the new role's chip against the old role's list forever.
 */
@Composable
private fun contributorsState(
    role: ContributorRole,
    openContributors: OpenContributors,
): ContributorsSession {
    val session = remember(role) { openContributors(role) }
    DisposableEffect(session) { onDispose { session.close() } }
    return session
}

/**
 * Opens a Contributor Detail session for [contributorId] and collects it, closing the previous one
 * whenever the person changes or the page goes away. Keyed on [contributorId] for the same reason
 * [bookDetailState] keys on `bookId` — a bare `remember { }` would keep showing the first person
 * forever after navigating to a second one.
 */
@Composable
private fun contributorDetailState(
    contributorId: String,
    openContributorDetail: OpenContributorDetail,
): ContributorDetailUiState {
    val session = remember(contributorId) { openContributorDetail(contributorId) }
    DisposableEffect(session) { onDispose { session.close() } }
    return session.state.collectAsState().value
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
private fun BookRouteContent(
    bookId: String,
    editingBookId: String?,
    router: Router,
    route: Route,
    openBookDetail: OpenBookDetail,
    openBookEdit: OpenBookEdit,
    playback: PlaybackSession,
) {
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
        return
    }

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
        onOpenContributor = { id -> router.navigate(Route(listOf(CONTRIBUTOR_KEY, id))) },
        onOpenSeries = { id -> router.navigate(Route(listOf(SERIES_KEY, id))) },
    )
}

/**
 * Opens a Series Detail session for [seriesId] and collects it, closing the previous one whenever
 * the series changes or the page goes away. Keyed on [seriesId] for the same reason
 * [contributorDetailState] keys on `contributorId` — a bare `remember { }` would keep showing the
 * first series forever after navigating to a second one.
 */
@Composable
private fun seriesDetailState(
    seriesId: String,
    openSeriesDetail: OpenSeriesDetail,
): SeriesDetailUiState {
    val session = remember(seriesId) { openSeriesDetail(seriesId) }
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

/**
 * Parses `?role=` at the route boundary, so [ContributorsPage] only ever receives a valid enum.
 *
 * Anything other than the narrator token is Author — absent, malformed ("banana"), and even a
 * genuinely valid-but-unsupported role ("editor") alike, because the Contributors route offers
 * exactly two chips and there is no third list for a third role to fall into. A junk value must
 * never light the wrong chip while claiming the wrong empty state.
 */
private fun parseContributorRole(raw: String?): ContributorRole =
    if (raw != null && ContributorRole.fromApiValue(raw) == ContributorRole.NARRATOR) {
        ContributorRole.NARRATOR
    } else {
        ContributorRole.AUTHOR
    }

/** Where selecting [facet] in the shared facet row navigates to. */
private fun routeFor(facet: LibraryFacet): Route =
    when (facet) {
        LibraryFacet.Books -> {
            Route(listOf(LIBRARY_KEY))
        }

        LibraryFacet.Authors -> {
            Route(listOf(LIBRARY_KEY, CONTRIBUTORS_KEY))
        }

        LibraryFacet.Narrators -> {
            Route(
                listOf(LIBRARY_KEY, CONTRIBUTORS_KEY),
                mapOf(ROLE_QUERY_KEY to NARRATOR_ROLE_VALUE),
            )
        }
    }

/** The shell's content region — the thing a page change fades. See [fadePageIn]. */
private const val SHELL_MAIN = ".shell-main"

private const val HOME_KEY = "home"

private const val DISCOVER_KEY = "discover"

private const val SHELF_KEY = "shelf"

private const val NEW_KEY = "new"

private const val BOOK_KEY = "book"

/** The trailing segment that turns a book route into its edit form. */
private const val EDIT_KEY = "edit"

private const val LIBRARY_KEY = "library"

/** The trailing segment that turns the Library route into the Contributors list. */
private const val CONTRIBUTORS_KEY = "contributors"

/** The path segment that opens a contributor's own page — `/contributor/{id}`. */
private const val CONTRIBUTOR_KEY = "contributor"

/** The path segment that opens a series in reading order — `/series/{id}`. */
private const val SERIES_KEY = "series"

/**
 * The routes reached FROM the library that still belong to it, though none of them is a `/library`
 * URL. Each keeps Library lit in the sidebar; leaving no entry highlighted reads as having
 * navigated out of the app.
 */
private val LIBRARY_DEEP_LINKS = setOf(BOOK_KEY, CONTRIBUTOR_KEY, SERIES_KEY)

private const val ROLE_QUERY_KEY = "role"

private const val NARRATOR_ROLE_VALUE = "narrator"

private const val SEARCH_KEY = "search"

/** The query-string key a search lives under: `/search?q=dune`. */
private const val SEARCH_QUERY_KEY = "q"

/**
 * Hit types with a real place to navigate to. Series and Tag detail routes don't exist yet — see
 * [SearchRoute]'s KDoc.
 */
private val SEARCH_OPENABLE_TYPES = setOf(SearchHitType.BOOK, SearchHitType.CONTRIBUTOR)

private val PRIMARY_NAV =
    NavSection(
        entries =
            listOf(
                NavEntry(HOME_KEY, "Home", WebIcon.Home),
                NavEntry(LIBRARY_KEY, "Library", WebIcon.Book),
                NavEntry(DISCOVER_KEY, "Discover", WebIcon.Compass),
                NavEntry("search", "Search", WebIcon.Search),
            ),
    )

private const val ADMIN_KEY = "admin"

private const val SETTINGS_KEY = "settings"

private const val DEVICES_KEY = "devices"

private val FOOTER_NAV =
    listOf(
        NavEntry(ADMIN_KEY, "Admin", WebIcon.Shield),
        NavEntry(SETTINGS_KEY, "Settings", WebIcon.Cog),
    )

/**
 * The `/shelf/{id}` branch — one shelf, its books, and the owner's controls.
 *
 * The session loads the shelf, keyed on [shelfId], so walking from one shelf to another replaces
 * the session rather than showing the previous shelf's books under the new one's name.
 */
@Composable
private fun ShelfDetailRoute(
    router: Router,
    openShelfDetail: OpenShelfDetail,
    shelfId: String,
    onHeroBookIdChange: (String) -> Unit,
) {
    val session = remember(shelfId) { openShelfDetail(shelfId) }
    DisposableEffect(session) { onDispose { session.close() } }

    // The ViewModel reports a refused mutation exactly once, into a channel. Held here so the page
    // can render it until dismissed — a message that vanished on the next recomposition would be
    // no better than the silence this replaced.
    var notice by remember(session) { mutableStateOf<String?>(null) }
    LaunchedEffect(session) { session.messages.collect { notice = it } }

    ShelfDetailPage(
        state = session.state.collectAsState().value,
        notice = notice,
        onDismissNotice = { notice = null },
        onOpenBook = { id ->
            onHeroBookIdChange(id)
            router.navigate(Route(listOf(BOOK_KEY, id)))
        },
        onRemoveBook = session.onRemoveBook,
        onReorder = session.onReorder,
        onEditShelf = { id -> router.navigate(Route(listOf(SHELF_KEY, id, EDIT_KEY))) },
        onOpenLibrary = { router.navigate(Route(listOf(LIBRARY_KEY))) },
    )
}

/**
 * The `/shelf/new` and `/shelf/{id}/edit` branches — one form, two modes.
 *
 * ## Where "back" goes
 *
 * `NavigateBack` is the only thing the ViewModel says on success, and it says the same word for a
 * save and for a delete — so the destination is decided here, where the difference is known:
 *
 * - **Created** a shelf → Home, which is where My Shelves lives. The new shelf's id never reaches
 *   this client, so the shelf itself is not a destination we could offer.
 * - **Saved** an edit → back to the shelf, which is what you were looking at.
 * - **Deleted** → Home. Returning to the shelf would land on its own "could not be opened" error,
 *   which is a true statement and a terrible way to confirm that a deletion worked.
 *
 * [deleted] exists only to tell the last two apart. It is set when Delete is pressed rather than
 * derived from state, because by the time `NavigateBack` arrives the shelf is already gone.
 */
@Composable
private fun ShelfEditRoute(
    router: Router,
    openShelfEdit: OpenShelfEdit,
    shelfId: String?,
) {
    val session = remember(shelfId) { openShelfEdit(shelfId) }
    DisposableEffect(session) { onDispose { session.close() } }
    var deleted by remember(shelfId) { mutableStateOf(false) }

    val goHome = { router.navigate(Route(emptyList())) }

    LaunchedEffect(session) {
        session.navActions.collect {
            if (shelfId == null || deleted) {
                goHome()
            } else {
                router.navigate(Route(listOf(SHELF_KEY, shelfId)))
            }
        }
    }

    ShelfEditPage(
        state = session.state.collectAsState().value,
        isEditing = shelfId != null,
        onSave = session.onSave,
        onDelete = {
            deleted = true
            session.onDelete()
        },
        onDismissError = session.onDismissError,
        onCancel = { if (shelfId == null) goHome() else router.navigate(Route(listOf(SHELF_KEY, shelfId))) },
    )
}

/**
 * The three shelf screens, behind the shell's single shelf branch.
 *
 * Split out so [RouteContent] has one condition for shelves rather than three. The URL grammar is
 * [shelfRouteOf]'s job; this only maps a parsed route to a screen.
 */
@Composable
private fun ShelfRouteContent(
    shelfRoute: ShelfRoute,
    router: Router,
    openShelfDetail: OpenShelfDetail,
    openShelfEdit: OpenShelfEdit,
    onHeroBookIdChange: (String) -> Unit,
) {
    when (shelfRoute) {
        is ShelfRoute.Create -> {
            ShelfEditRoute(router = router, openShelfEdit = openShelfEdit, shelfId = null)
        }

        is ShelfRoute.Edit -> {
            ShelfEditRoute(router = router, openShelfEdit = openShelfEdit, shelfId = shelfRoute.shelfId)
        }

        is ShelfRoute.Detail -> {
            ShelfDetailRoute(
                router = router,
                openShelfDetail = openShelfDetail,
                shelfId = shelfRoute.shelfId,
                onHeroBookIdChange = onHeroBookIdChange,
            )
        }
    }
}

/**
 * The `/settings` branch.
 *
 * No router: nothing on this page navigates. Every control writes a preference and the effect is
 * the page itself changing — the theme most visibly, since it repaints the whole shell.
 */
@Composable
private fun SettingsRoute(
    router: Router,
    openSettings: OpenSettings,
) {
    val session = remember { openSettings() }
    DisposableEffect(session) { onDispose { session.close() } }

    SettingsPage(
        state = session.state.collectAsState().value,
        onThemeMode = session.onThemeMode,
        onDefaultSpeed = session.onDefaultSpeed,
        onSkipForward = session.onSkipForward,
        onSkipBackward = session.onSkipBackward,
        onAutoRewind = session.onAutoRewind,
        onIgnoreTitleArticles = session.onIgnoreTitleArticles,
        onHideSingleBookSeries = session.onHideSingleBookSeries,
        onOpenDevices = { router.navigate(Route(listOf(SETTINGS_KEY, DEVICES_KEY))) },
    )
}

/**
 * The `/settings/devices` branch.
 *
 * `nowMs` is read once per visit, as on Discover: every timestamp here is at least a minute old, so
 * a ticking clock would re-render the list each second to change nothing anyone is watching.
 */
@Composable
private fun DevicesRoute(openDevices: OpenDevices) {
    val session = remember { openDevices() }
    DisposableEffect(session) { onDispose { session.close() } }
    val nowMs = remember { currentEpochMilliseconds() }

    DevicesPage(
        state = session.state.collectAsState().value,
        nowMs = nowMs,
        onRevoke = session.onRevoke,
        // The callback exists so a caller can navigate after the fact; signing out drops the whole
        // shell on its own, so there is nothing for this one to do.
        onSignOutEverywhere = { session.onSignOutEverywhere {} },
        onRetry = session.onRetry,
    )
}

/**
 * The `/admin` branch — the people on this server.
 *
 * The sidebar entry is already gated on `observeIsAdmin`, so this route is only reachable by an
 * admin through the UI. That is a convenience, not the guard: every action here is owner-gated
 * server-side, which is what actually stops a typed URL.
 */
@Composable
private fun AdminRoute(openAdmin: OpenAdmin) {
    val session = remember { openAdmin() }
    DisposableEffect(session) { onDispose { session.close() } }
    val nowMs = remember { currentEpochMilliseconds() }

    AdminPage(
        state = session.state.collectAsState().value,
        nowMs = nowMs,
        onApproveUser = session.onApproveUser,
        onDenyUser = session.onDenyUser,
        onDeleteUser = session.onDeleteUser,
        onRevokeInvite = session.onRevokeInvite,
        onDecidePasswordReset = session.onDecidePasswordReset,
        onDismissResetCode = session.onDismissResetCode,
        onSetRegistrationPolicy = session.onSetRegistrationPolicy,
        onClearError = session.onClearError,
        onRetry = session.onRetry,
    )
}

/**
 * Whether these segments belong to the account family — settings, devices, admin.
 *
 * Grouped for the same reason the shelf routes were: [RouteContent]'s chain grows a branch per
 * screen and tripped its complexity limit on the third of these. One condition here, one `when`
 * below, and the shell's chain stops caring how many account screens exist.
 */
private fun isAccountRoute(
    segments: List<String>,
    active: String,
): Boolean = active == SETTINGS_KEY || active == ADMIN_KEY || segments.firstOrNull() == SETTINGS_KEY

/** The account family: settings, the devices beneath it, and admin. */
@Composable
private fun AccountRouteContent(
    segments: List<String>,
    active: String,
    router: Router,
    openSettings: OpenSettings,
    openDevices: OpenDevices,
    openAdmin: OpenAdmin,
) {
    when {
        segments.firstOrNull() == SETTINGS_KEY && segments.getOrNull(1) == DEVICES_KEY -> {
            DevicesRoute(openDevices = openDevices)
        }

        active == ADMIN_KEY -> {
            AdminRoute(openAdmin = openAdmin)
        }

        active == SETTINGS_KEY -> {
            SettingsRoute(router = router, openSettings = openSettings)
        }

        // A `/settings/anything-else` URL. Falls through to the shell's own not-found rather than
        // silently showing Settings, so a mistyped path says so.
        else -> {
            PagePlaceholder(active)
        }
    }
}
