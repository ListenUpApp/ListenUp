package com.calypsan.listenup.web.features.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.web.features.library.BookSelectionScaffold
import com.calypsan.listenup.web.features.library.FeedSelection
import com.calypsan.listenup.web.features.nowplaying.PlaybackSession
import com.calypsan.listenup.web.features.search.SearchRoute
import com.calypsan.listenup.web.nav.Router
import com.calypsan.listenup.web.nav.Routes
import com.calypsan.listenup.web.nav.rememberSession
import com.calypsan.listenup.web.shell.RouteContent

/**
 * The `/` branch of [RouteContent] — Home's session lifecycle, its destinations and the play entry.
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
internal fun HomeRoute(
    router: Router,
    openHome: OpenHome,
    feeds: FeedSelection,
    playback: PlaybackSession,
) {
    val session = rememberSession { openHome() }
    val actions =
        HomeActions(
            onOpenBook = { id ->
                feeds.onHeroBookIdChange(id)
                router.navigate(Routes.Book.detail(id))
            },
            // P-R5: the play entry Book Detail and Series use.
            onPlay = { id -> playback.onPlayBook(BookId(id), null) },
            onOpenLibrary = { router.navigate(Routes.library) },
            onOpenDiscover = { router.navigate(Routes.discover) },
            onOpenShelf = { id -> router.navigate(Routes.shelf(id)) },
            onCreateShelf = { router.navigate(Routes.newShelf) },
            onRetry = session.onRetry,
            onRetryStats = session.onRetryStats,
            onOpenReaders = { id ->
                feeds.onHeroBookIdChange(id)
                router.navigate(Routes.Book.detail(id))
            },
            onDismissFinished = session.onDismissFinished,
            onRateFinished = session.onRateFinished,
            onRetryConnection = session.onRetryConnection,
        )

    BookSelectionScaffold(feeds.openMultiSelect, router, feeds.onToast) { selection ->
        HomePage(
            state = session.state.collectAsState().value,
            stats = session.stats.collectAsState().value,
            actions = actions,
            isWide = rememberHomeIsWide(),
            selection = selection,
        )
    }
}
