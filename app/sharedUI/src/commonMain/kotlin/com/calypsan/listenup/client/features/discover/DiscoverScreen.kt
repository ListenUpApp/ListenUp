package com.calypsan.listenup.client.features.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.util.PlatformBackHandler
import com.calypsan.listenup.client.features.library.components.BookSelectionScaffold
import com.calypsan.listenup.client.features.shell.LocalShellLandings
import com.calypsan.listenup.client.features.shell.ShellLanding
import com.calypsan.listenup.client.features.shell.components.AppHeaderSlot
import com.calypsan.listenup.client.presentation.books.BookMultiSelectViewModel
import com.calypsan.listenup.client.presentation.books.SelectionMode
import com.calypsan.listenup.client.presentation.discover.ActivityFeedViewModel
import com.calypsan.listenup.client.presentation.discover.DiscoverShelvesUiState
import com.calypsan.listenup.client.presentation.discover.DiscoverViewModel
import com.calypsan.listenup.client.presentation.discover.LeaderboardViewModel
import org.koin.compose.viewmodel.koinViewModel

/**
 * Discover screen - browse shelves from other users and view community leaderboard.
 *
 * Features:
 * - Community leaderboard with gamified rankings
 * - Pull to refresh
 * - Users grouped with their shelves
 * - Click shelf to view details
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(
    appHeader: AppHeaderSlot,
    onShelfClick: (String) -> Unit,
    onBookClick: (String) -> Unit,
    onUserProfileClick: (String) -> Unit,
    onEditSelected: ((List<String>, endSelection: () -> Unit) -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(),
    modifier: Modifier = Modifier,
    viewModel: DiscoverViewModel = koinViewModel(),
    multiSelect: BookMultiSelectViewModel = koinViewModel(),
    leaderboardViewModel: LeaderboardViewModel = koinViewModel(),
    activityFeedViewModel: ActivityFeedViewModel = koinViewModel(),
) {
    val shelvesState by viewModel.discoverShelvesState.collectAsStateWithLifecycle()
    val listeningState by viewModel.currentlyListeningState.collectAsStateWithLifecycle()
    val picksState by viewModel.discoverBooksState.collectAsStateWithLifecycle()
    val recentState by viewModel.recentlyAddedState.collectAsStateWithLifecycle()
    val leaderboardState by leaderboardViewModel.uiState.collectAsStateWithLifecycle()
    val leaderboardSelection by leaderboardViewModel.selection.collectAsStateWithLifecycle()
    val activityState by activityFeedViewModel.state.collectAsStateWithLifecycle()
    val haptics = LocalHaptics.current
    val landings = LocalShellLandings.current
    val selectionMode by multiSelect.selectionMode.collectAsStateWithLifecycle()
    val isInSelectionMode = selectionMode is SelectionMode.Active
    val selectedBookIds = (selectionMode as? SelectionMode.Active)?.selectedIds.orEmpty()

    // Handle back press to exit selection mode.
    PlatformBackHandler(enabled = isInSelectionMode) {
        multiSelect.exitSelectionMode()
    }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
    ) {
        PullToRefreshBox(
            isRefreshing = shelvesState is DiscoverShelvesUiState.Loading,
            onRefresh = {
                haptics.thresholdActivate()
                // One pull reaches every section: each ViewModel restarts only what failed.
                viewModel.refresh()
                leaderboardViewModel.refresh()
                activityFeedViewModel.refresh()
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            DiscoverLayout(
                state =
                    DiscoverState(
                        listening = listeningState,
                        picks = picksState,
                        leaderboard = leaderboardState,
                        leaderboardSelection = leaderboardSelection,
                        activity = activityState,
                        shelves = shelvesState,
                        recentlyAdded = recentState,
                    ),
                actions =
                    DiscoverActions(
                        onBookClick = { id ->
                            if (isInSelectionMode) multiSelect.toggleSelection(id) else onBookClick(id)
                        },
                        onShelfClick = onShelfClick,
                        onUserClick = onUserProfileClick,
                        onShuffle = viewModel::shuffle,
                        onSelectPeriod = leaderboardViewModel::selectPeriod,
                        onSelectCategory = leaderboardViewModel::selectCategory,
                        onOpenLibraryNewest = { landings?.request(ShellLanding.LibraryNewestFirst) },
                        onBookLongPress = multiSelect::enterSelectionMode,
                    ),
                appHeader = appHeader,
                contentPadding = contentPadding,
                isInSelectionMode = isInSelectionMode,
                selectedBookIds = selectedBookIds,
            )
        }

        // Multi-select overlay: top toolbar, picker sheets, and success feedback.
        BookSelectionScaffold(multiSelect = multiSelect, onEditSelected = onEditSelected)
    }
}
