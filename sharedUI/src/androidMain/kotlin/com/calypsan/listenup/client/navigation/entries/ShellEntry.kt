package com.calypsan.listenup.client.navigation.entries

import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.calypsan.listenup.client.design.LocalDeviceContext
import com.calypsan.listenup.client.domain.model.FacetKind
import com.calypsan.listenup.client.features.library.LibraryScreen
import com.calypsan.listenup.client.features.nowplaying.NowPlayingBar
import com.calypsan.listenup.client.features.setup.scan.LibraryScanScreen
import com.calypsan.listenup.client.features.shell.AppShell
import com.calypsan.listenup.client.features.shell.ShellDestination
import com.calypsan.listenup.client.navigation.Admin
import com.calypsan.listenup.client.navigation.BookDetail
import com.calypsan.listenup.client.navigation.BrowseFacet
import com.calypsan.listenup.client.navigation.ContributorDetail
import com.calypsan.listenup.client.navigation.SeriesDetail
import com.calypsan.listenup.client.navigation.Settings
import com.calypsan.listenup.client.navigation.Shell
import com.calypsan.listenup.client.navigation.ShelfDetail
import com.calypsan.listenup.client.navigation.UserProfile
import com.calypsan.listenup.client.presentation.library.LibraryViewModel
import com.calypsan.listenup.client.presentation.nowplaying.NowPlayingViewModel
import com.calypsan.listenup.client.presentation.startup.LibraryReadiness
import org.koin.compose.viewmodel.koinViewModel

/** Shell (root) navigation entry — the main app scaffold with bottom nav. */
internal fun EntryProviderScope<NavKey>.shellEntry(
    backStack: NavBackStack<NavKey>,
    currentShellDestination: () -> ShellDestination,
    onDestinationChange: (ShellDestination) -> Unit,
    nowPlayingViewModel: NowPlayingViewModel,
    readiness: () -> LibraryReadiness,
    onSignOut: () -> Unit,
    onContinueToPartialLibrary: () -> Unit,
) {
    entry<Shell> {
        // Read the hoisted state INSIDE the entry content (not as a captured parameter) so the
        // shell recomposes when the selected tab or readiness changes. NavDisplay does not
        // re-invoke this entry builder when only those values change (the Shell back-stack key is
        // unchanged), so a captured snapshot would go stale — bottom-nav taps would no-op.
        val readinessState = readiness()

        // Readiness gate: while the initial library population is running we show a
        // dedicated full-screen progress screen and DO NOT mount the shell — so the
        // user never navigates an empty app, and the Library grid + Coil don't decode
        // a thousand covers while the sync/catch-up is still churning (which exhausted
        // the heap → OOM). Populating spans the server scan AND the client import, so
        // when it clears the books are already in Room (see applyScanEvent).
        (readinessState as? LibraryReadiness.Populating)?.let { populating ->
            LibraryScanScreen(
                scanProgress = populating.progress,
                stalled = populating.stalled,
                onContinue = onContinueToPartialLibrary,
            )
            return@entry
        }

        // Preload library data by injecting LibraryViewModel early
        @Suppress("UNUSED_VARIABLE")
        val libraryViewModel: LibraryViewModel = koinViewModel()

        // Get search state for overlay
        AppShell(
            currentDestination = currentShellDestination(),
            onDestinationChange = onDestinationChange,
            nowPlayingContent = {
                val nowPlayingScreenState by nowPlayingViewModel
                    .screenState
                    .collectAsStateWithLifecycle()
                // Deferred read: the 4 Hz position tick recomposes only the scrubber leaf inside
                // NowPlayingBar, not this shell content or the bar chrome.
                val nowPlayingProgressState =
                    nowPlayingViewModel
                        .progress
                        .collectAsStateWithLifecycle()
                NowPlayingBar(
                    state = nowPlayingScreenState.state,
                    progress = { nowPlayingProgressState.value },
                    isExpanded = nowPlayingScreenState.isExpanded,
                    onTap = nowPlayingViewModel::expand,
                    onPlayPause = nowPlayingViewModel::playPause,
                    onSkipBack = { nowPlayingViewModel.skipBack() },
                )
            },
            onBookClick = { bookId ->
                backStack.add(BookDetail(bookId))
            },
            onSeriesClick = { seriesId ->
                backStack.add(SeriesDetail(seriesId))
            },
            onContributorClick = { contributorId ->
                backStack.add(ContributorDetail(contributorId))
            },
            onTagClick = { tagId, tagName ->
                backStack.add(BrowseFacet(kind = FacetKind.Tag, facetId = tagId, facetName = tagName))
            },
            onAdminClick =
                if (!LocalDeviceContext.current.isLeanback) {
                    { backStack.add(Admin) }
                } else {
                    null
                },
            onSettingsClick = {
                backStack.add(Settings)
            },
            onSignOut = onSignOut,
            onUserProfileClick = { userId ->
                backStack.add(UserProfile(userId))
            },
            homeContent = { padding, appHeader, onNavigateToLibrary ->
                com.calypsan.listenup.client.features.home.HomeScreen(
                    appHeader = appHeader,
                    onBookClick = { bookId -> backStack.add(BookDetail(bookId)) },
                    onNavigateToLibrary = onNavigateToLibrary,
                    onShelfClick = { shelfId -> backStack.add(ShelfDetail(shelfId)) },
                    onSeeAllShelves = onNavigateToLibrary,
                    contentPadding = padding,
                )
            },
            libraryContent = { padding, appHeader ->
                LibraryScreen(
                    onBookClick = { bookId -> backStack.add(BookDetail(bookId)) },
                    onSeriesClick = { seriesId -> backStack.add(SeriesDetail(seriesId)) },
                    onAuthorClick = { authorId -> backStack.add(ContributorDetail(authorId)) },
                    onNarratorClick = { narratorId ->
                        backStack.add(ContributorDetail(narratorId))
                    },
                    appHeader = appHeader,
                    modifier = Modifier.padding(padding),
                )
            },
            discoverContent = { padding, appHeader ->
                com.calypsan.listenup.client.features.discover.DiscoverScreen(
                    appHeader = appHeader,
                    onShelfClick = { shelfId -> backStack.add(ShelfDetail(shelfId)) },
                    onBookClick = { bookId -> backStack.add(BookDetail(bookId)) },
                    onUserProfileClick = { userId -> backStack.add(UserProfile(userId)) },
                    contentPadding = padding,
                )
            },
        )
    }
}
