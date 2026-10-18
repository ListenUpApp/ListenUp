package com.calypsan.listenup.client.features.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.features.discover.components.ActivityFeedSection
import com.calypsan.listenup.client.features.discover.components.DiscoverLeaderboardSection
import com.calypsan.listenup.client.features.discover.components.ListeningNowSection
import com.calypsan.listenup.client.features.discover.components.RecentlyAddedSection
import com.calypsan.listenup.client.features.discover.components.ShelvesByOthersSection
import com.calypsan.listenup.client.features.discover.components.SomethingNewSection
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardPeriod
import com.calypsan.listenup.client.features.shell.LandingEffect
import com.calypsan.listenup.client.features.shell.ShellDestination
import com.calypsan.listenup.client.features.shell.ShellLanding
import com.calypsan.listenup.client.features.shell.components.AppHeaderSlot

/** Stable `LazyColumn` keys for Discover's sections; the landing scrolls by them (Task 14). */
internal object DiscoverItemKey {
    const val HEADER = "header"
    const val LISTENING = "listening"
    const val PICKS = "picks"
    const val LEADERBOARD = "leaderboard"
    const val ACTIVITY = "activity"
    const val SHELVES = "shelves"
    const val RECENT = "recent"

    /**
     * Item order. Every section item is always emitted, even when its section draws nothing, so an
     * index here is stable; on wide layouts the [Leaderboard | Activity] row takes [LEADERBOARD]'s slot.
     */
    val ORDER = listOf(HEADER, LISTENING, PICKS, LEADERBOARD, ACTIVITY, SHELVES, RECENT)
}

/**
 * Holds Discover's list state and answers the shell's leaderboard landing (spec §6.4): Week, then the
 * leaderboard scrolled into view. Selecting first means the board arrives already on the right period.
 */
@Composable
internal fun DiscoverLanding(
    onSelectPeriod: (LeaderboardPeriod) -> Unit,
    content: @Composable (LazyListState) -> Unit,
) {
    val listState = rememberLazyListState()
    LandingEffect(ShellLanding.DiscoverLeaderboard) {
        onSelectPeriod(LeaderboardPeriod.Week)
        listState.animateScrollToItem(DiscoverItemKey.ORDER.indexOf(DiscoverItemKey.LEADERBOARD))
    }
    content(listState)
}

/**
 * Discover's page, drawn from [state] alone: every section in order, each rendering its own sealed
 * state. Stateless, so a host test can drive section order, geometry and the landing without a
 * ViewModel; [DiscoverScreen] collects the state and hands it here.
 */
@Composable
internal fun DiscoverLayout(
    state: DiscoverState,
    actions: DiscoverActions,
    appHeader: AppHeaderSlot,
    contentPadding: PaddingValues,
    isInSelectionMode: Boolean,
    selectedBookIds: Set<String>,
    listState: LazyListState = rememberLazyListState(),
) {
    val windowSizeClass = currentWindowAdaptiveInfoV2().windowSizeClass
    // ≥ 600dp: the community sits side by side. ≥ 840dp (spec §3.4): every section uses the width.
    val isWide = windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
    val isExpanded = windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)

    LazyColumn(
        state = listState,
        // Shell system-bar/nav insets fold into the feed's padding so content scrolls under the bars
        // and rests clear of them — no outer pad that would clip the first/last item. The top takes
        // the insets alone, as Home and Library do, so the header sits at the same height on every tab.
        contentPadding =
            PaddingValues(
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding() + 16.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(Spacing.sectionGap),
        modifier = Modifier.fillMaxSize(),
    ) {
        // Custom shell header scrolls with the feed (search/sync/avatar live here).
        item(key = DiscoverItemKey.HEADER) {
            appHeader {
                Text(
                    text = ShellDestination.Discover.title,
                    style = MaterialTheme.typography.headlineMediumEmphasized,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        item(key = DiscoverItemKey.LISTENING) {
            ListeningNowSection(
                state = state.listening,
                community = state.community,
                viewer = state.viewer,
                onInvite = actions.onInvite,
                onRetry = actions.onRetryListening,
                onBookClick = actions.onBookClick,
                onUserClick = actions.onUserClick,
                isInSelectionMode = isInSelectionMode,
                selectedBookIds = selectedBookIds,
                onBookLongPress = actions.onBookLongPress,
                wide = isExpanded,
            )
        }

        item(key = DiscoverItemKey.PICKS) {
            SomethingNewSection(
                state = state.picks,
                onShuffle = actions.onShuffle,
                // A fresh draw is the picks' restart.
                onRetry = actions.onShuffle,
                onBrowseLibrary = actions.onBrowseLibrary,
                onBookClick = actions.onBookClick,
                isInSelectionMode = isInSelectionMode,
                selectedBookIds = selectedBookIds,
                onBookLongPress = actions.onBookLongPress,
                wide = isExpanded,
            )
        }

        if (isWide) {
            // The [Leaderboard | Activity] row takes the leaderboard's key, so the landing's index holds.
            item(key = DiscoverItemKey.LEADERBOARD) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.screenMargin),
                    horizontalArrangement = Arrangement.spacedBy(if (isExpanded) 20.dp else Spacing.lg),
                    verticalAlignment = Alignment.Top,
                ) {
                    LeaderboardItem(state, actions, Modifier.weight(1f))
                    // Board Discover-AndroidTablet: 1fr : 1.25fr.
                    ActivityItem(state, actions, Modifier.weight(if (isExpanded) ACTIVITY_WEIGHT_EXPANDED else 1f))
                }
            }
        } else {
            item(key = DiscoverItemKey.LEADERBOARD) {
                LeaderboardItem(state, actions, Modifier.padding(horizontal = Spacing.screenMargin))
            }
            item(key = DiscoverItemKey.ACTIVITY) {
                ActivityItem(state, actions, Modifier.padding(horizontal = Spacing.screenMargin))
            }
        }

        item(key = DiscoverItemKey.SHELVES) {
            ShelvesByOthersSection(
                state = state.shelves,
                onShelfClick = actions.onShelfClick,
                onUserClick = actions.onUserClick,
                onRetry = actions.onRetryShelves,
                wide = isExpanded,
            )
        }

        item(key = DiscoverItemKey.RECENT) {
            RecentlyAddedSection(
                state = state.recentlyAdded,
                onBookClick = actions.onBookClick,
                onOpenLibraryNewest = actions.onOpenLibraryNewest,
                onRetry = actions.onRetryRecent,
                isInSelectionMode = isInSelectionMode,
                selectedBookIds = selectedBookIds,
                onBookLongPress = actions.onBookLongPress,
                wide = isExpanded,
            )
        }
    }
}

/** The leaderboard, wherever the layout places it. */
@Composable
private fun LeaderboardItem(
    state: DiscoverState,
    actions: DiscoverActions,
    modifier: Modifier,
) {
    DiscoverLeaderboardSection(
        state = state.leaderboard,
        selection = state.leaderboardSelection,
        community = state.community,
        onRetry = actions.onRetryLeaderboard,
        onSelectPeriod = actions.onSelectPeriod,
        onSelectCategory = actions.onSelectCategory,
        onUserClick = actions.onUserClick,
        modifier = modifier,
    )
}

/** The activity feed, wherever the layout places it. */
@Composable
private fun ActivityItem(
    state: DiscoverState,
    actions: DiscoverActions,
    modifier: Modifier,
) {
    ActivityFeedSection(
        state = state.activity,
        onBookClick = actions.onBookClick,
        onShelfClick = actions.onShelfClick,
        onUserClick = actions.onUserClick,
        onRetry = actions.onRetryActivity,
        modifier = modifier,
        onAddToShelf = actions.onAddToShelf,
        onSeeAll = actions.onOpenAllActivity,
    )
}

/** The activity column's share beside the leaderboard's 1 on an expanded window. */
private const val ACTIVITY_WEIGHT_EXPANDED = 1.25f
