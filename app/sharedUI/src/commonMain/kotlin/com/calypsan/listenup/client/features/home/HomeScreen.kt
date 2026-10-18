package com.calypsan.listenup.client.features.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.util.PlatformBackHandler
import com.calypsan.listenup.client.features.library.components.BookSelectionScaffold
import com.calypsan.listenup.client.features.shell.LocalShellLandings
import com.calypsan.listenup.client.features.shell.ShellLanding
import com.calypsan.listenup.client.features.shell.components.AppHeaderSlot
import com.calypsan.listenup.client.presentation.books.BookMultiSelectViewModel
import com.calypsan.listenup.client.presentation.books.SelectionMode
import com.calypsan.listenup.client.presentation.home.HomeStatsUiState
import com.calypsan.listenup.client.presentation.home.HomeStatsViewModel
import com.calypsan.listenup.client.presentation.home.HomeUiState
import com.calypsan.listenup.client.presentation.home.HomeViewModel
import org.koin.compose.viewmodel.koinViewModel

/**
 * Home screen: resolves Home's two ViewModels and draws [HomeBody] (C12). Pull-to-refresh and multi-select live here;
 * everything Home draws lives in [HomeBody], [HomeLoadingBody] and [HomeErrorBody].
 *
 * @param appHeader The shell header slot; the greeting is its leading content.
 * @param onBookClick Opens a book.
 * @param onPlayBook The player's play entry, from the saved position (P-R5, C18).
 * @param onNavigateToLibrary Opens the Library: Browse the Library, and In Library where the shell has no landings.
 * @param onNavigateToDiscover Opens Discover: the quiet slots' Discover button, and the stats footer's leaderboard link
 *   where the shell has no landings. Inside the shell, In Library and the footer land through [LocalShellLandings].
 * @param onShelfClick Opens a shelf.
 * @param onCreateShelf Starts a new shelf.
 * @param onOpenReaders Opens a book with its readers in view (the finishers line); the plain book where there is none.
 * @param onSeeAllShelves Opens the My shelves list (null = this host has none, so See all is not offered).
 * @param onEditSelected Navigate to the bulk metadata editor with the current multi-selection,
 *   with a callback that ends the selection once an apply has landed
 *   (null = this host has no route to the editor, so the action is not offered)
 * @param contentPadding The shell's insets, applied inside the scroll.
 * @param modifier Modifier from parent
 * @param viewModel HomeViewModel injected via Koin
 * @param multiSelect Multi-select state shared with Library
 * @param statsViewModel HomeStatsViewModel injected via Koin
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    appHeader: AppHeaderSlot,
    onBookClick: (String) -> Unit,
    onPlayBook: (String) -> Unit,
    onNavigateToLibrary: () -> Unit,
    onNavigateToDiscover: () -> Unit,
    onShelfClick: (String) -> Unit,
    onCreateShelf: () -> Unit,
    onOpenReaders: (String) -> Unit = onBookClick,
    onSeeAllShelves: (() -> Unit)? = null,
    onEditSelected: ((List<String>, endSelection: () -> Unit) -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(),
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = koinViewModel(),
    multiSelect: BookMultiSelectViewModel = koinViewModel(),
    statsViewModel: HomeStatsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val stats by statsViewModel.uiState.collectAsStateWithLifecycle()
    val landings = LocalShellLandings.current
    val actions =
        HomeActions(
            onOpenBook = onBookClick,
            onPlay = onPlayBook,
            onOpenLibrary = onNavigateToLibrary,
            onOpenLeaderboard = {
                landings?.request(ShellLanding.DiscoverLeaderboard) ?: onNavigateToDiscover()
            },
            onOpenInProgress = {
                landings?.request(ShellLanding.LibraryInProgress) ?: onNavigateToLibrary()
            },
            onOpenShelf = onShelfClick,
            onCreateShelf = onCreateShelf,
            onOpenDiscover = onNavigateToDiscover,
            onDismissFinished = viewModel::dismissFinished,
            onRateFinished = viewModel::rateFinished,
            onOpenReaders = onOpenReaders,
            onRetry = viewModel::refresh,
            onSeeAllShelves = onSeeAllShelves,
        )

    Scaffold(
        containerColor = Color.Transparent,
        // The shell owns the system-bar/nav insets and passes them in as [contentPadding]; this
        // inner Scaffold must not re-add them, or the top/bottom would be inset twice.
        contentWindowInsets = WindowInsets(left = 0, top = 0, right = 0, bottom = 0),
        modifier = modifier.fillMaxSize(),
    ) { paddingValues ->
        when (val s = state) {
            is HomeUiState.Loading -> {
                HomeLoadingBody(
                    width = rememberHomeWidth(),
                    appHeader = appHeader,
                    contentPadding = contentPadding,
                    modifier = Modifier.padding(paddingValues),
                )
            }

            is HomeUiState.Error -> {
                HomeErrorBody(
                    state = s,
                    onRetry = viewModel::refresh,
                    appHeader = appHeader,
                    contentPadding = contentPadding,
                    modifier = Modifier.padding(paddingValues),
                )
            }

            is HomeUiState.Ready -> {
                HomeReady(
                    state = s,
                    stats = stats,
                    actions = actions,
                    multiSelect = multiSelect,
                    appHeader = appHeader,
                    onRefresh = {
                        viewModel.refresh()
                        statsViewModel.refresh()
                    },
                    onRetryStats = statsViewModel::refresh,
                    onEditSelected = onEditSelected,
                    contentPadding = contentPadding,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeReady(
    state: HomeUiState.Ready,
    stats: HomeStatsUiState,
    actions: HomeActions,
    multiSelect: BookMultiSelectViewModel,
    appHeader: AppHeaderSlot,
    onRefresh: () -> Unit,
    onRetryStats: () -> Unit,
    onEditSelected: ((List<String>, endSelection: () -> Unit) -> Unit)?,
    contentPadding: PaddingValues,
) {
    val haptics = LocalHaptics.current
    val width = rememberHomeWidth()
    val selectionMode by multiSelect.selectionMode.collectAsStateWithLifecycle()
    val active = selectionMode as? SelectionMode.Active
    val selection =
        HomeSelection(
            isActive = active != null,
            selectedIds = active?.selectedIds.orEmpty(),
            onToggle = multiSelect::toggleSelection,
            onEnter = multiSelect::enterSelectionMode,
        )

    // Back exits selection mode.
    PlatformBackHandler(enabled = selection.isActive) {
        multiSelect.exitSelectionMode()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = state.isLoading,
            onRefresh = {
                haptics.thresholdActivate()
                onRefresh()
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            HomeBody(
                state = state,
                stats = stats,
                width = width,
                actions = actions,
                selection = selection,
                appHeader = appHeader,
                contentPadding = contentPadding,
                onRetryStats = onRetryStats,
            )
        }

        // Multi-select overlay: top toolbar, picker sheets, and success feedback.
        BookSelectionScaffold(
            multiSelect = multiSelect,
            onEditSelected = onEditSelected,
            contentPadding = contentPadding,
        )
    }
}
