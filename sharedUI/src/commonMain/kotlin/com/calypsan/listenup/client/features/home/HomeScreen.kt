package com.calypsan.listenup.client.features.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import com.calypsan.listenup.client.design.util.PlatformBackHandler
import com.calypsan.listenup.client.features.home.components.ContinueListeningRow
import com.calypsan.listenup.client.features.home.components.EmptyContinueListening
import com.calypsan.listenup.client.features.home.components.HomeHeader
import com.calypsan.listenup.client.features.home.components.HomeStatsSection
import com.calypsan.listenup.client.features.home.components.MyShelvesRow
import com.calypsan.listenup.client.features.library.components.BookSelectionScaffold
import com.calypsan.listenup.client.features.shell.components.AppHeaderSlot
import com.calypsan.listenup.client.playback.PlaybackManager
import com.calypsan.listenup.client.presentation.books.BookMultiSelectViewModel
import com.calypsan.listenup.client.presentation.books.SelectionMode
import com.calypsan.listenup.client.presentation.home.HomeUiState
import com.calypsan.listenup.client.presentation.home.HomeViewModel
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.theme.Spacing
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * Home screen - personalized landing page.
 *
 * Adaptive layout:
 * - Compact: single column (header, continue listening, stats, shelves)
 * - Medium+: stats and shelves render side-by-side below continue listening
 *
 * @param onBookClick Callback when a book is clicked
 * @param onNavigateToLibrary Callback to navigate to the library
 * @param onShelfClick Callback when a shelf is clicked
 * @param onSeeAllShelves Callback when "See All" is clicked for shelves
 * @param modifier Modifier from parent
 * @param viewModel HomeViewModel injected via Koin
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    appHeader: AppHeaderSlot,
    onBookClick: (String) -> Unit,
    onNavigateToLibrary: () -> Unit,
    onShelfClick: (String) -> Unit,
    onSeeAllShelves: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = koinViewModel(),
    multiSelect: BookMultiSelectViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Observe the currently-playing book so its Continue Listening card gets the now-playing frame.
    val playback: PlaybackManager = koinInject()
    val playingBookId by playback.currentBookId.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.snackbarMessages.collect { snackbarHostState.showSnackbar(it) }
    }

    val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass
    val isWide =
        windowSizeClass.isWidthAtLeastBreakpoint(
            WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND,
        )

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color.Transparent,
        // The shell owns the system-bar/nav insets and passes them in as [contentPadding]; this
        // inner Scaffold must not re-add them, or the top/bottom would be inset twice.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        modifier = modifier.fillMaxSize(),
    ) { paddingValues ->
        when (val s = state) {
            is HomeUiState.Loading -> {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(paddingValues),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            is HomeUiState.Error -> {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(paddingValues),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = s.message,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            is HomeUiState.Ready -> {
                HomeContent(
                    state = s,
                    isWide = isWide,
                    playingBookId = playingBookId?.value,
                    multiSelect = multiSelect,
                    appHeader = appHeader,
                    onRefresh = { viewModel.refresh() },
                    onBookClick = onBookClick,
                    onNavigateToLibrary = onNavigateToLibrary,
                    onShelfClick = onShelfClick,
                    onSeeAllShelves = onSeeAllShelves,
                    contentPadding = contentPadding,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeContent(
    state: HomeUiState.Ready,
    isWide: Boolean,
    playingBookId: String?,
    multiSelect: BookMultiSelectViewModel,
    appHeader: AppHeaderSlot,
    onRefresh: () -> Unit,
    onBookClick: (String) -> Unit,
    onNavigateToLibrary: () -> Unit,
    onShelfClick: (String) -> Unit,
    onSeeAllShelves: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHaptics.current
    val selectionMode by multiSelect.selectionMode.collectAsStateWithLifecycle()
    val isInSelectionMode = selectionMode is SelectionMode.Active
    val selectedBookIds = (selectionMode as? SelectionMode.Active)?.selectedIds.orEmpty()

    // Handle back press to exit selection mode.
    PlatformBackHandler(enabled = isInSelectionMode) {
        multiSelect.exitSelectionMode()
    }

    Box(modifier = modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = state.isLoading,
            onRefresh = {
                haptics.thresholdActivate()
                onRefresh()
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            // The shell's system-bar/nav insets are applied *inside* the scroll so content scrolls
            // edge-to-edge under the bars and rests clear of them — not clipped by an outer pad.
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(contentPadding),
                verticalArrangement = Arrangement.spacedBy(Spacing.sectionGap),
            ) {
                // The shell header scrolls away with the page; the greeting is its leading hero.
                appHeader {
                    HomeHeader(timeGreeting = state.timeGreeting, userName = state.userName, isWide = isWide)
                }

                if (state.hasContinueListening) {
                    ContinueListeningRow(
                        items = state.continueListening,
                        onBookClick = { bookId ->
                            if (isInSelectionMode) multiSelect.toggleSelection(bookId) else onBookClick(bookId)
                        },
                        playingBookId = playingBookId,
                        isInSelectionMode = isInSelectionMode,
                        selectedBookIds = selectedBookIds,
                        onBookLongPress = multiSelect::enterSelectionMode,
                    )
                } else {
                    EmptyContinueListening(onBrowseLibrary = onNavigateToLibrary)
                }

                if (isWide) {
                    HomeContentWide(
                        state = state,
                        onShelfClick = onShelfClick,
                        onSeeAllShelves = onSeeAllShelves,
                    )
                } else {
                    HomeContentCompact(
                        state = state,
                        onShelfClick = onShelfClick,
                        onSeeAllShelves = onSeeAllShelves,
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        // Multi-select overlay: top toolbar, picker sheets, and success feedback.
        BookSelectionScaffold(multiSelect = multiSelect)
    }
}

@Composable
private fun HomeContentWide(
    state: HomeUiState.Ready,
    onShelfClick: (String) -> Unit,
    onSeeAllShelves: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        HomeStatsSection(isWide = true, modifier = Modifier.weight(1.7f))
        if (state.hasMyShelves) {
            MyShelvesRow(
                shelves = state.myShelves,
                isWide = true,
                onShelfClick = onShelfClick,
                onSeeAllClick = onSeeAllShelves,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun HomeContentCompact(
    state: HomeUiState.Ready,
    onShelfClick: (String) -> Unit,
    onSeeAllShelves: () -> Unit,
) {
    HomeStatsSection(
        isWide = false,
        modifier = Modifier.padding(horizontal = Spacing.screenMargin),
    )

    if (state.hasMyShelves) {
        // The parent Column already spaces siblings by sectionGap; an extra Spacer here would
        // triple the stats→shelves gap.
        MyShelvesRow(
            shelves = state.myShelves,
            isWide = false,
            onShelfClick = onShelfClick,
            onSeeAllClick = onSeeAllShelves,
        )
    }
}
