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
import com.calypsan.listenup.client.features.shell.ShellDestination
import com.calypsan.listenup.client.features.shell.components.AppHeaderSlot

/** Stable `LazyColumn` keys for Discover's sections; the landing scrolls by them (Task 14). */
internal object DiscoverItemKey {
    const val HEADER = "header"
    const val LISTENING = "listening"
    const val PICKS = "picks"
    const val LEADERBOARD = "leaderboard"
    const val ACTIVITY = "activity"
    const val COMMUNITY = "community" // [Leaderboard | Activity] in one row on wide layouts
    const val SHELVES = "shelves"
    const val RECENT = "recent"
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
    val isWide =
        currentWindowAdaptiveInfoV2().windowSizeClass.isWidthAtLeastBreakpoint(
            WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND,
        )

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
                onBookClick = actions.onBookClick,
                onUserClick = actions.onUserClick,
                isInSelectionMode = isInSelectionMode,
                selectedBookIds = selectedBookIds,
                onBookLongPress = actions.onBookLongPress,
            )
        }

        item(key = DiscoverItemKey.PICKS) {
            SomethingNewSection(
                state = state.picks,
                onShuffle = actions.onShuffle,
                onBookClick = actions.onBookClick,
                isInSelectionMode = isInSelectionMode,
                selectedBookIds = selectedBookIds,
                onBookLongPress = actions.onBookLongPress,
            )
        }

        if (isWide) {
            item(key = DiscoverItemKey.COMMUNITY) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.screenMargin),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
                ) {
                    DiscoverLeaderboardSection(
                        state = state.leaderboard,
                        selection = state.leaderboardSelection,
                        onSelectPeriod = actions.onSelectPeriod,
                        onSelectCategory = actions.onSelectCategory,
                        onUserClick = actions.onUserClick,
                        modifier = Modifier.weight(1f),
                    )
                    ActivityFeedSection(
                        state = state.activity,
                        onBookClick = actions.onBookClick,
                        onShelfClick = actions.onShelfClick,
                        onUserClick = actions.onUserClick,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        } else {
            item(key = DiscoverItemKey.LEADERBOARD) {
                DiscoverLeaderboardSection(
                    state = state.leaderboard,
                    selection = state.leaderboardSelection,
                    onSelectPeriod = actions.onSelectPeriod,
                    onSelectCategory = actions.onSelectCategory,
                    onUserClick = actions.onUserClick,
                    modifier = Modifier.padding(horizontal = Spacing.screenMargin),
                )
            }
            item(key = DiscoverItemKey.ACTIVITY) {
                ActivityFeedSection(
                    state = state.activity,
                    onBookClick = actions.onBookClick,
                    onShelfClick = actions.onShelfClick,
                    onUserClick = actions.onUserClick,
                    modifier = Modifier.padding(horizontal = Spacing.screenMargin),
                )
            }
        }

        item(key = DiscoverItemKey.SHELVES) {
            ShelvesByOthersSection(
                state = state.shelves,
                onShelfClick = actions.onShelfClick,
                onUserClick = actions.onUserClick,
            )
        }

        item(key = DiscoverItemKey.RECENT) {
            RecentlyAddedSection(
                state = state.recentlyAdded,
                onBookClick = actions.onBookClick,
                isInSelectionMode = isInSelectionMode,
                selectedBookIds = selectedBookIds,
                onBookLongPress = actions.onBookLongPress,
            )
        }
    }
}
