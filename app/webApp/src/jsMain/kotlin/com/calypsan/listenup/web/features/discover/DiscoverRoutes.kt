package com.calypsan.listenup.web.features.discover

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import com.calypsan.listenup.core.currentEpochMilliseconds
import com.calypsan.listenup.web.features.home.HomeRoute
import com.calypsan.listenup.web.features.library.BookSelectionScaffold
import com.calypsan.listenup.web.features.library.FeedSelection
import com.calypsan.listenup.web.nav.Router
import com.calypsan.listenup.web.nav.Routes
import com.calypsan.listenup.web.nav.rememberSession
import com.calypsan.listenup.web.shell.nowMs

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
internal fun DiscoverRoute(
    router: Router,
    openDiscover: OpenDiscover,
    feeds: FeedSelection,
    landOnLeaderboard: Boolean = false,
) {
    val session = rememberSession { openDiscover() }
    val nowMs = remember { currentEpochMilliseconds() }

    val viewer = session.viewer.collectAsState().value

    BookSelectionScaffold(feeds.openMultiSelect, router, feeds.onToast) { selection ->
        DiscoverPage(
            books = session.books.collectAsState().value,
            recentlyAdded = session.recentlyAdded.collectAsState().value,
            currentlyListening = session.currentlyListening.collectAsState().value,
            leaderboard = session.leaderboard.collectAsState().value,
            leaderboardSelection = session.leaderboardSelection.collectAsState().value,
            activity = session.activity.collectAsState().value,
            shelves = session.shelves.collectAsState().value,
            nowMs = nowMs,
            onOpenBook = { id ->
                feeds.onHeroBookIdChange(id)
                router.navigate(Routes.Book.detail(id))
            },
            onOpenShelf = { id -> router.navigate(Routes.shelf(id)) },
            onOpenProfile = { id -> router.navigate(Routes.profile(id)) },
            onSelectPeriod = session.onSelectPeriod,
            onSelectCategory = session.onSelectCategory,
            onRetryListening = session.onRetryListening,
            onRetryRecent = session.onRetryRecent,
            onRetryShelves = session.onRetryShelves,
            onRefreshLeaderboard = session.onRefreshLeaderboard,
            onRefreshActivity = session.onRefreshActivity,
            onShuffle = session.onShuffle,
            onOpenLibraryNewest = { router.navigate(Routes.libraryNewestFirst) },
            communitySize = session.communitySize.collectAsState().value,
            viewer = viewer,
            // Only admins invite: no button, and no gap where it was, for a member.
            onInvite = { router.navigate(Routes.Admin.invite) }.takeIf { viewer?.canInvite == true },
            onBrowseLibrary = { router.navigate(Routes.library) },
            landOnLeaderboard = landOnLeaderboard,
            selection = selection,
        )
    }
}
