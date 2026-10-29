package com.calypsan.listenup.web.shell

import com.calypsan.listenup.core.BookId
import androidx.compose.runtime.Composable
import com.calypsan.listenup.web.WebAppRoot
import com.calypsan.listenup.web.WebGraph
import com.calypsan.listenup.web.design.ShowActionToast
import com.calypsan.listenup.web.features.admin.InboxBadgeState
import com.calypsan.listenup.web.features.bookdetail.BookRouteContent
import com.calypsan.listenup.web.features.browse.BrowseRouteContent
import com.calypsan.listenup.web.features.bulkedit.BulkEditRoute
import com.calypsan.listenup.web.features.contributordetail.ContributorRouteContent
import com.calypsan.listenup.web.features.discover.DiscoverRouteContent
import com.calypsan.listenup.web.features.home.HomeRoute
import com.calypsan.listenup.web.features.library.FeedSelection
import com.calypsan.listenup.web.features.library.LibraryRouteContent
import com.calypsan.listenup.web.features.library.LibrarySession
import com.calypsan.listenup.web.features.library.SeriesListRoute
import com.calypsan.listenup.web.features.notifications.NotificationsRoute
import com.calypsan.listenup.web.features.nowplaying.PlaybackSession
import com.calypsan.listenup.web.features.profile.ProfileRouteContent
import com.calypsan.listenup.web.features.search.SearchRouteContent
import com.calypsan.listenup.web.features.seriesdetail.SeriesRouteContent
import com.calypsan.listenup.web.features.settings.AccountRouteContent
import com.calypsan.listenup.web.features.settings.isAccountRoute
import com.calypsan.listenup.web.features.shelf.ShelfRouteContent
import com.calypsan.listenup.web.features.shelf.shelfRouteOf
import com.calypsan.listenup.web.nav.BOOK_KEY
import com.calypsan.listenup.web.nav.CONTRIBUTORS_KEY
import com.calypsan.listenup.web.nav.CONTRIBUTOR_KEY
import com.calypsan.listenup.web.nav.DISCOVER_KEY
import com.calypsan.listenup.web.nav.EDIT_KEY
import com.calypsan.listenup.web.nav.HOME_KEY
import com.calypsan.listenup.web.nav.LIBRARY_KEY
import com.calypsan.listenup.web.nav.NOTIFICATIONS_KEY
import com.calypsan.listenup.web.nav.PLAYING_KEY
import com.calypsan.listenup.web.nav.PROFILE_KEY
import com.calypsan.listenup.web.nav.Route
import com.calypsan.listenup.web.nav.Router
import com.calypsan.listenup.web.nav.Routes
import com.calypsan.listenup.web.nav.SEARCH_KEY
import com.calypsan.listenup.web.nav.SERIES_KEY
import com.calypsan.listenup.web.nav.browseTarget
import com.calypsan.listenup.web.nav.bulkEditIds
import com.calypsan.listenup.web.nav.editTargetOf
import com.calypsan.listenup.web.nav.idUnder
import com.calypsan.listenup.web.nav.isServed

/**
 * The one page the current route actually shows — Book Edit, Book Detail, Contributors, Library
 * or the placeholder — pulled out of [WebAppRoot] itself purely to keep that function's branching
 * readable as a single glance rather than one long `if`/`else if` chain. Now Playing is the exception: it has a slot
 * of its own (NowPlayingSlot), so this draws nothing for `/playing`.
 */
@Composable
internal fun RouteContent(
    router: Router,
    route: Route,
    page: String,
    active: String,
    graph: WebGraph,
    currentUserId: String?,
    onToast: (String) -> Unit,
    onActionToast: ShowActionToast,
    librarySession: LibrarySession,
    inbox: InboxBadgeState,
    playback: PlaybackSession,
    heroBookId: String?,
    onHeroBookIdChange: (String) -> Unit,
) {
    val shelfRoute = shelfRouteOf(route.segments)
    val bulkEditIds = route.bulkEditIds()
    val feeds = FeedSelection(graph.openMultiSelect, onHeroBookIdChange, onToast)
    val bookId = route.idUnder(BOOK_KEY)
    // `/library/contributors` — the second segment turns the Library route into the people
    // behind it, rather than a route of its own, so the sidebar stays lit on Library either way.
    val isContributors = active == LIBRARY_KEY && route.segments.getOrNull(1) == CONTRIBUTORS_KEY
    val isSeriesList = active == LIBRARY_KEY && route.segments.getOrNull(1) == SERIES_KEY
    // `/contributor/{id}` — the person behind the books, a route of its own (unlike the list, one
    // book's worth of detail is not a facet of anything else).
    val contributorId = route.idUnder(CONTRIBUTOR_KEY)
    // `/series/{id}` — a route of its own for the same reason a contributor's page is one: a
    // series is something you arrive at and link to, not a filter over the library grid.
    val seriesId = route.idUnder(SERIES_KEY)
    // `/tag/{id}`, `/mood/{id}`, `/genre/{id}` — the chips on a book lead somewhere. Each is a
    // route of its own rather than a filter over the library grid, for the reason a series is:
    // it is a place you arrive at and send someone a link to.
    val browseTarget = route.browseTarget()
    // `/profile/{id}` — a listener's own page, reached from a notification, the account menu,
    // or a link someone sent. A route of its own for the same reason a contributor's is.
    val profileId = route.idUnder(PROFILE_KEY)
    // `/profile/{id}/edit` — a route of its own rather than a mode of the profile, for the same
    // reason `/book/{id}/edit` is one: the form is linkable, Back leaves it, and a half-finished
    // edit cannot be mistaken for the profile it will become.
    // Read only inside the profile branch below, so it does not re-test `profileId != null` —
    // `/book/{id}/edit` sets this too, and never reaches anything that looks at it.
    val editingProfile = route.segments.getOrNull(2) == EDIT_KEY

    if (!route.isServed()) {
        NotFoundPage(onGoHome = { router.navigate(Routes.home) })
    } else if (bulkEditIds.isNotEmpty()) {
        BulkEditRoute(router = router, openBulkEdit = graph.openBulkEdit, bookIds = bulkEditIds, onToast = onToast)
    } else if (page == PLAYING_KEY) {
        // Now Playing draws in its own slot beside this one (NowPlayingSlot), so the player morph can hold it over the
        // page it rose from without remounting either.
        Unit
    } else if (bookId != null) {
        BookRouteContent(
            bookId = bookId,
            router = router,
            route = route,
            openBookDetail = graph.openBookDetail,
            openBookEdit = graph.openBookEdit,
            openChapterEditor = graph.openChapterEditor,
            matchDetails = graph.matchDetails,
            openBookReaders = graph.openBookReaders,
            openBookRatings = graph.openBookRatings,
            openHardcoverMatch = graph.openHardcoverMatch,
            openBookHardcover = graph.openBookHardcover,
            onToast = onToast,
            onActionToast = onActionToast,
            playback = playback,
            currentUserId = currentUserId,
        )
    } else if (isContributors || contributorId != null) {
        ContributorRouteContent(
            isList = isContributors,
            librarySession = librarySession,
            contributorId = contributorId,
            route = route,
            router = router,
            graph = graph,
            currentUserId = currentUserId,
            onToast = onToast,
        )
    } else if (profileId != null) {
        ProfileRouteContent(
            userId = profileId,
            editing = editingProfile,
            currentUserId = currentUserId,
            router = router,
            openProfile = graph.openProfile,
            openEditProfile = graph.openEditProfile,
        )
    } else if (browseTarget != null) {
        BrowseRouteContent(
            target = browseTarget,
            router = router,
            openBrowseFacet = graph.openBrowseFacet,
            openGenreDestination = graph.openGenreDestination,
        )
    } else if (page == NOTIFICATIONS_KEY) {
        NotificationsRoute(router = router, openNotifications = graph.openNotifications)
    } else if (seriesId != null) {
        SeriesRouteContent(
            seriesId = seriesId,
            editingSeriesId = route.editTargetOf(seriesId),
            router = router,
            openSeriesDetail = graph.openSeriesDetail,
            openSeriesEdit = graph.openSeriesEdit,
            playback = playback,
        )
    } else if (isSeriesList) {
        // ⛔ Before the bare Library branch: `/library/series` is also an `active == LIBRARY_KEY`,
        // so testing that first renders the book grid under a Series chip — which is precisely what
        // this route did until a spec caught it.
        SeriesListRoute(librarySession = librarySession, router = router)
    } else if (active == LIBRARY_KEY) {
        LibraryRouteContent(
            librarySession = librarySession,
            inbox = inbox,
            feeds = feeds,
            router = router,
            heroBookId = heroBookId,
            query = route.query,
            onPlayBook = { id -> playback.onPlayBook(BookId(id), null) },
        )
    } else if (page == SEARCH_KEY) {
        SearchRouteContent(router = router, route = route, openSearch = graph.openSearch, openSeeAll = graph.openSeeAll)
    } else if (shelfRoute != null) {
        // ⛔ Before Home: `/shelves` keeps Home lit, so it is also an `active == HOME_KEY`.
        ShelfRouteContent(
            shelfRoute = shelfRoute,
            router = router,
            openShelfDetail = graph.openShelfDetail,
            openShelfEdit = graph.openShelfEdit,
            openMyShelves = graph.openMyShelves,
            onHeroBookIdChange = onHeroBookIdChange,
        )
    } else if (active == HOME_KEY) {
        HomeRoute(router, graph.openHome, feeds, playback)
    } else if (isAccountRoute(route.segments, active)) {
        AccountRouteContent(
            route = route,
            active = active,
            router = router,
            openSettings = graph.openSettings,
            openDevices = graph.openDevices,
            openHardcover = graph.openHardcover,
            openAdmin = graph.openAdmin,
            openNotificationPrefs = graph.openNotificationPrefs,
            openLicences = graph.openLicences,
            admin = graph.admin,
            onToast = onToast,
            onActionToast = onActionToast,
        )
    } else if (active == DISCOVER_KEY) {
        DiscoverRouteContent(router, route, graph.openDiscover, graph.openActivityFeed, feeds)
    } else {
        NotFoundPage(onGoHome = { router.navigate(Routes.home) })
    }
}
