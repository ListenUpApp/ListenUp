package com.calypsan.listenup.web

import androidx.compose.runtime.Composable
import com.calypsan.listenup.web.features.books.LocalSelectionHandOff
import com.calypsan.listenup.web.features.books.SelectionHandOff
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.web.design.LocalAvatarFrames
import com.calypsan.listenup.web.design.LocalCoverThemes
import com.calypsan.listenup.web.design.LocalCompositionProbe
import com.calypsan.listenup.web.design.LocalRestrictedBookIds
import com.calypsan.listenup.web.design.ShowActionToast
import com.calypsan.listenup.web.features.nowplaying.PlaybackSession
import com.calypsan.listenup.web.features.nowplaying.PlayerShortcutKeys
import com.calypsan.listenup.web.features.nowplaying.PlayingReturn
import com.calypsan.listenup.web.features.nowplaying.TransportBarHost
import com.calypsan.listenup.web.features.sync.DeadLetterNotice
import com.calypsan.listenup.web.motion.PageArrival
import com.calypsan.listenup.web.nav.HOME_KEY
import com.calypsan.listenup.web.nav.HOME_DEEP_LINKS
import com.calypsan.listenup.web.nav.LIBRARY_DEEP_LINKS
import com.calypsan.listenup.web.nav.LIBRARY_KEY
import com.calypsan.listenup.web.nav.PLAYING_KEY
import com.calypsan.listenup.web.nav.Route
import com.calypsan.listenup.web.nav.Router
import com.calypsan.listenup.web.nav.Routes
import com.calypsan.listenup.web.nav.ScrollRestoration
import com.calypsan.listenup.web.nav.rememberSession
import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.ButtonSize
import com.calypsan.listenup.web.design.MenuAction
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.design.buttonClasses
import com.calypsan.listenup.web.features.sync.SyncIndicatorButton
import com.calypsan.listenup.web.features.sync.SyncStatusLine
import com.calypsan.listenup.web.features.sync.rememberMinuteClock
import com.calypsan.listenup.web.nav.ADMIN_KEY
import com.calypsan.listenup.web.nav.CONTRIBUTORS_KEY
import com.calypsan.listenup.web.nav.DISCOVER_KEY
import com.calypsan.listenup.web.nav.SERIES_KEY
import com.calypsan.listenup.web.nav.NOTIFICATIONS_KEY
import com.calypsan.listenup.web.nav.SETTINGS_KEY
import com.calypsan.listenup.web.shell.PhoneTopBar
import kotlinx.browser.document
import org.jetbrains.compose.web.dom.Div
import org.w3c.dom.HTMLElement
import com.calypsan.listenup.web.shell.AccountIdentity
import com.calypsan.listenup.web.shell.AccountMenu
import com.calypsan.listenup.web.design.DocumentTitle
import com.calypsan.listenup.web.design.DocumentTitleWriter
import com.calypsan.listenup.web.design.LocalDocumentTitle
import com.calypsan.listenup.web.features.nowplaying.playingTitleFor
import com.calypsan.listenup.web.shell.CommandPaletteHost
import com.calypsan.listenup.web.shell.LocalWindowKeys
import com.calypsan.listenup.web.shell.WindowKeys
import com.calypsan.listenup.web.shell.RouteContent
import com.calypsan.listenup.web.shell.Shell
import com.calypsan.listenup.web.shell.footerNav
import com.calypsan.listenup.web.shell.inboxBadge
import com.calypsan.listenup.web.shell.libraryState
import com.calypsan.listenup.web.shell.notificationBadge
import com.calypsan.listenup.web.shell.playbackState
import com.calypsan.listenup.web.shell.primaryNav
import com.calypsan.listenup.web.shell.restrictedBooks

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
 * [graph] is everything the shell opens; see [WebGraph] for why none of it has a default.
 */
@Composable
fun WebAppRoot(
    router: Router,
    graph: WebGraph,
    onToast: (String) -> Unit,
    onActionToast: ShowActionToast,
    onSignOut: () -> Unit = {},
) {
    var collapsed by remember { mutableStateOf(false) }
    // Starts false so a member never sees the entry flash; an admin's entry appears the moment
    // the repository's flow answers — the same read Book Edit gates its Collections field on.
    val isAdmin by remember { graph.observeIsAdmin() }.collectAsState(initial = false)
    // Starts null so the account menu never flashes a "Your profile" entry whose destination is
    // not yet known — the same reason `isAdmin` starts false.
    val currentUserId by remember { graph.observeCurrentUserId() }.collectAsState(initial = null)
    val currentUserName by remember { graph.observeCurrentUserName() }.collectAsState(initial = null)
    val account = currentUserId?.let { id -> AccountIdentity(userId = id, name = currentUserName.orEmpty()) }

    // Which grid tile is the shared element. Set on the way into a book and kept afterwards, so the
    // flight works in both directions: out to the detail hero, and back to the same tile on return.
    // Back restores the grid's scroll first (ScrollRestoration), so the tile is on screen again
    // before the flight measures it — see HeroFlight.
    var heroBookId by remember { mutableStateOf<String?>(null) }
    // Opened once for the shell's lifetime, not per route: the badge is on the sidebar, which
    // outlives every page. Closing it with a route would blank the count the moment you navigated
    // away from the one page that proves it was right.
    val unreadCount = notificationBadge(graph.openNotificationBell)
    // Shell-lifetime, like the unread count: the held count rides Library in the sidebar, and the
    // Library page's inbox strip reads the same session.
    val inbox = inboxBadge(graph.admin.inboxBadge)
    // Shell-lifetime too: the lock on every book card, on every page, reads this one set.
    val restrictedBookIds = restrictedBooks(graph.admin.restrictedBooks)
    val playback = playbackState(graph.openPlayback)
    // One per window: where Now Playing closes to, and which control gets focus back (S4).
    val playingReturn = remember { PlayingReturn() }
    // The one window key listener (spec §5.8): the palette, the player and a page's own keys register with it.
    val windowKeys = remember { WindowKeys() }
    // The tab's one title (spec §5.7): the page's name, or what is playing while it plays.
    val documentTitle = remember { DocumentTitle() }
    DisposableEffect(windowKeys) {
        val remove = windowKeys.install()
        onDispose { remove() }
    }
    val route = router.current
    val page = route.segments.firstOrNull() ?: HOME_KEY
    // A book, the person behind it, or the series it belongs to all live in the library, so
    // every one of those deep links keeps Library lit in the sidebar.
    val active =
        when (page) {
            in LIBRARY_DEEP_LINKS -> LIBRARY_KEY
            in HOME_DEEP_LINKS -> HOME_KEY
            else -> page
        }

    // Shell-lifetime, like the unread count: the phone header and the sidebar both say it.
    val sync = rememberSession { graph.openSyncIndicator() }
    val syncState by sync.state.collectAsState()
    val now = rememberMinuteClock()
    // The phone header names the top-level pages it heads; Home's greeting is its own title.
    val phoneTitle = phoneTitleFor(route)
    val openProfile: (() -> Unit)? = currentUserId?.let { id -> { router.navigate(Routes.profile(id)) } }

    CompositionLocalProvider(
        LocalWindowKeys provides windowKeys,
        LocalDocumentTitle provides documentTitle,
        LocalRestrictedBookIds provides restrictedBookIds,
        LocalAvatarFrames provides graph.observeAvatarFrame,
        LocalCoverThemes provides graph.coverThemes,
    ) {
        Shell(
            sections = listOf(primaryNav(heldCount = inbox.heldCount)),
            active = active,
            // The whole path, not just the first segment the fade keys on: `/book/42` → `/book/42/edit`
            // is a new page with a new heading, even though it does not fade.
            pageKey = route.segments.joinToString("/"),
            collapsed = collapsed,
            footer = footerNav(isAdmin = isAdmin, unreadCount = unreadCount),
            onToggleCollapse = { collapsed = !collapsed },
            onNavigate = { key ->
                val segments = if (key == HOME_KEY) emptyList() else listOf(key)
                router.navigate(Route(segments))
            },
            titled = phoneTitle != null,
            topBar = {
                ShellPhoneTopBar(
                    router = router,
                    title = phoneTitle,
                    unreadCount = unreadCount,
                    isAdmin = isAdmin,
                    sync = { SyncIndicatorButton(state = syncState, now = now, onRefresh = sync.onRefresh) },
                    onSignOut = onSignOut,
                    onOpenProfile = openProfile,
                    account = account,
                )
            },
            sidebarStatus = { SyncStatusLine(state = syncState, now = now, onRefresh = sync.onRefresh) },
            immersive = page == PLAYING_KEY,
        ) {
            LocalCompositionProbe.current(SHELL_CONTENT_PROBE)
            // The desktop's account menu; a phone's lives in its header (see PhoneTopBar).
            Div(attrs = { classes("shell-account") }) {
                AccountMenu(onSignOut = onSignOut, onOpenProfile = openProfile, account = account)
            }
            // Opened once for the shell's lifetime rather than per visit. Closing it on the way to a
            // book meant coming back rebuilt the ViewModel and re-queried all 1,204 rows — measured at
            // **478 ms** of "Loading…" every single time, for a list the reader had just been looking
            // at. A Room-backed flow costs almost nothing to keep subscribed, and keeping it is what
            // makes going back instant instead of merely fast.
            val librarySession = libraryState(graph.openLibrary)
            ScrollRestoration(route)
            // One per window: carries a selection to the bulk editor and back.
            val selectionHandOff = remember { SelectionHandOff() }
            PageArrival(page) {
                CompositionLocalProvider(LocalSelectionHandOff provides selectionHandOff) {
                    RouteContent(
                        router = router,
                        route = route,
                        page = page,
                        active = active,
                        graph = graph,
                        currentUserId = currentUserId,
                        onToast = onToast,
                        onActionToast = onActionToast,
                        librarySession = librarySession,
                        inbox = inbox,
                        playback = playback,
                        playingReturn = playingReturn,
                        heroBookId = heroBookId,
                        onHeroBookIdChange = { heroBookId = it },
                    )
                }
            }

            // Above the player bar, and unlike a playback problem it is not dismissible: a failed edit stays
            // until the reader retries or accepts the server's version. See [DeadLetterNotice].
            val deadLetters = rememberSession { graph.openDeadLetters() }
            DeadLetterNotice(
                failed = deadLetters.failed.collectAsState().value,
                onRetry = deadLetters.onRetry,
                onDismiss = deadLetters.onDismiss,
                onRetryAll = deadLetters.onRetryAll,
                onDismissAll = deadLetters.onDismissAll,
            )

            // Last inside the content region, so it sits under whatever page is showing and stays put as the
            // reader moves between them. It says what is wrong itself; the cause and Dismiss are in Now Playing.
            // Its own scope: the playback tick recomposes the bar, not this lambda. See the host.
            TransportBarHost(
                playback = playback,
                // `navigate`, not `replace`: leaving the player for a book is a page change, and Back returns.
                onOpenBook = { id -> router.navigate(Routes.Book.detail(id)) },
                onOpenInbox = { router.navigate(Routes.Admin.inbox) },
                // Now Playing is a page (S4): the bar opens it, and on it the bar's chevron is the way back.
                isNowPlayingOpen = page == PLAYING_KEY,
                onOpenNowPlaying = { opener -> playingReturn.open(router, opener) },
                onCloseNowPlaying = { playingReturn.close(router) },
                onFocusChapters = ::focusNowPlayingChapters,
            )

            PlayingTitle(playback, documentTitle)
            DocumentTitleWriter(documentTitle)

            // The player's keyboard, on every page while a book is loaded (spec §5.8).
            PlayerShortcutKeys(
                playback = playback,
                onPlayingPage = page == PLAYING_KEY,
                onTogglePlayer = { opener ->
                    if (page == PLAYING_KEY) playingReturn.close(router) else playingReturn.open(router, opener)
                },
                onClosePlayer = { playingReturn.close(router) },
            )

            // Last of all: the palette overlays everything above it, including the transport bar.
            CommandPaletteHost(router = router, openSearch = graph.openSearch)
        }
    }
}

/** Tells [title] what is playing, so the tab names it on every page while it plays (spec §5.7). */
@Composable
private fun PlayingTitle(
    playback: PlaybackSession,
    title: DocumentTitle,
) {
    val playing = playingTitleFor(playback.state.collectAsState().value)
    SideEffect { title.playing = playing }
}

/** On `/playing` the chapter list is already on the page: the bar's Chapters takes focus to its current row. */
private fun focusNowPlayingChapters() {
    (document.querySelector(".np-list .np-row.on") as? HTMLElement)?.focus()
}

/** What the shell's content lambda reports to [LocalCompositionProbe] each time it runs. */
internal const val SHELL_CONTENT_PROBE = "shell-content"

/**
 * The phone header, wired to the router: Search and Notifications navigate, and the account menu takes the sidebar
 * footer's Admin (for an admin) and Settings, which have no tab of their own below 760px.
 */
@Composable
private fun ShellPhoneTopBar(
    router: Router,
    title: String?,
    unreadCount: Int,
    isAdmin: Boolean,
    sync: @Composable () -> Unit,
    onSignOut: () -> Unit,
    onOpenProfile: (() -> Unit)?,
    account: AccountIdentity?,
) {
    PhoneTopBar(
        title = title,
        unread = unreadCount,
        onSearch = { router.navigate(Routes.search) },
        onNotifications = { router.navigate(Route(listOf(NOTIFICATIONS_KEY))) },
        sync = sync,
        account = {
            AccountMenu(
                onSignOut = onSignOut,
                onOpenProfile = onOpenProfile,
                account = account,
                destinations =
                    buildList {
                        if (isAdmin) {
                            add(
                                MenuAction("Admin", WebIcon.Shield, { router.navigate(Route(listOf(ADMIN_KEY))) }),
                            )
                        }
                        add(MenuAction("Settings", WebIcon.Cog, { router.navigate(Route(listOf(SETTINGS_KEY))) }))
                    },
                triggerClasses = arrayOf(*buttonClasses(ButtonKind.Icon, ButtonSize.Md), "ptb-b"),
            )
        },
    )
}

/**
 * What the phone header names for [route]: "Library" across all four Library sections, as Android's header does, and
 * "Discover". Home's greeting is its own title, and a page deeper in has its own heading.
 */
internal fun phoneTitleFor(route: Route): String? =
    when (route.segments) {
        listOf(LIBRARY_KEY),
        listOf(LIBRARY_KEY, SERIES_KEY),
        listOf(LIBRARY_KEY, CONTRIBUTORS_KEY),
        -> "Library"

        listOf(DISCOVER_KEY) -> "Discover"

        else -> null
    }
