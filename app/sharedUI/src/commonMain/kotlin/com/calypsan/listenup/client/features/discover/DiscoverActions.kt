package com.calypsan.listenup.client.features.discover

import androidx.compose.runtime.Immutable
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardCategory
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardPeriod
import com.calypsan.listenup.client.presentation.discover.ActivityFeedUiState
import com.calypsan.listenup.client.presentation.discover.CurrentlyListeningUiState
import com.calypsan.listenup.client.presentation.discover.DiscoverBooksUiState
import com.calypsan.listenup.client.presentation.discover.DiscoverShelvesUiState
import com.calypsan.listenup.client.presentation.discover.LeaderboardSelection
import com.calypsan.listenup.client.presentation.discover.LeaderboardUiState
import com.calypsan.listenup.client.presentation.discover.RecentlyAddedUiState

/**
 * Everything Discover shows, collected once by [DiscoverScreen] from its three ViewModels.
 *
 * A plain value so [DiscoverLayout] is stateless and a host test can render any combination of
 * section states without a ViewModel.
 */
@Immutable
data class DiscoverState(
    val listening: CurrentlyListeningUiState,
    val picks: DiscoverBooksUiState,
    val leaderboard: LeaderboardUiState,
    val leaderboardSelection: LeaderboardSelection,
    val activity: ActivityFeedUiState,
    val shelves: DiscoverShelvesUiState,
    val recentlyAdded: RecentlyAddedUiState,
)

/** What a tap on Discover can ask for. Navigation callbacks come from the shell; the rest are VM actions. */
@Immutable
data class DiscoverActions(
    val onBookClick: (String) -> Unit,
    val onShelfClick: (String) -> Unit,
    val onUserClick: (String) -> Unit,
    val onShuffle: () -> Unit,
    val onSelectPeriod: (LeaderboardPeriod) -> Unit,
    val onSelectCategory: (LeaderboardCategory) -> Unit,
    val onOpenLibraryNewest: () -> Unit,
    val onBookLongPress: ((String) -> Unit)?,
)
