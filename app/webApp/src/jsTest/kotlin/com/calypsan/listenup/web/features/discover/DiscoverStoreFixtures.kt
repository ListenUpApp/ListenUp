package com.calypsan.listenup.web.features.discover

import com.calypsan.listenup.client.domain.leaderboard.LeaderboardCategory
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardPeriod
import com.calypsan.listenup.client.presentation.discover.ActivityFeedUiState
import com.calypsan.listenup.client.presentation.discover.CommunitySize
import com.calypsan.listenup.client.presentation.discover.CurrentlyListeningUiState
import com.calypsan.listenup.client.presentation.discover.DiscoverBooksUiState
import com.calypsan.listenup.client.presentation.discover.DiscoverShelvesUiState
import com.calypsan.listenup.client.presentation.discover.DiscoverViewer
import com.calypsan.listenup.client.presentation.discover.LeaderboardSelection
import com.calypsan.listenup.client.presentation.discover.LeaderboardUiState
import com.calypsan.listenup.client.presentation.discover.RecentlyAddedUiState
import kotlinx.coroutines.flow.MutableStateFlow

/** A session over states that never change — the shape specs use in place of the graph. */
fun fixedDiscover(
    books: DiscoverBooksUiState = DiscoverBooksUiState.Loading,
    recentlyAdded: RecentlyAddedUiState = RecentlyAddedUiState.Loading,
    currentlyListening: CurrentlyListeningUiState = CurrentlyListeningUiState.Loading,
    shelves: DiscoverShelvesUiState = DiscoverShelvesUiState.Loading,
    leaderboard: LeaderboardUiState = LeaderboardUiState.Loading,
    leaderboardSelection: LeaderboardSelection = LeaderboardSelection(LeaderboardPeriod.Week, LeaderboardCategory.Time),
    activity: ActivityFeedUiState = ActivityFeedUiState.Loading,
    communitySize: CommunitySize = CommunitySize.Unknown,
    viewer: DiscoverViewer? = DiscoverViewer("me", "Simon", canInvite = true),
    onSelectPeriod: (LeaderboardPeriod) -> Unit = {},
    onSelectCategory: (LeaderboardCategory) -> Unit = {},
    onShuffle: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onRetryListening: () -> Unit = {},
    onRetryRecent: () -> Unit = {},
    onRetryShelves: () -> Unit = {},
    onRefreshLeaderboard: () -> Unit = {},
    onRefreshActivity: () -> Unit = {},
): OpenDiscover =
    {
        DiscoverSession(
            books = MutableStateFlow(books),
            recentlyAdded = MutableStateFlow(recentlyAdded),
            currentlyListening = MutableStateFlow(currentlyListening),
            shelves = MutableStateFlow(shelves),
            leaderboard = MutableStateFlow(leaderboard),
            leaderboardSelection = MutableStateFlow(leaderboardSelection),
            activity = MutableStateFlow(activity),
            communitySize = MutableStateFlow(communitySize),
            viewer = MutableStateFlow(viewer),
            onSelectPeriod = onSelectPeriod,
            onSelectCategory = onSelectCategory,
            onShuffle = onShuffle,
            onRefresh = onRefresh,
            onRetryListening = onRetryListening,
            onRetryRecent = onRetryRecent,
            onRetryShelves = onRetryShelves,
            onRefreshLeaderboard = onRefreshLeaderboard,
            onRefreshActivity = onRefreshActivity,
            close = {},
        )
    }
