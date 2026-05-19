package com.calypsan.listenup.client.features.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import com.calypsan.listenup.client.features.home.components.ContinueListeningRow
import com.calypsan.listenup.client.features.home.components.EmptyContinueListening
import com.calypsan.listenup.client.features.home.components.HomeHeader
import com.calypsan.listenup.client.features.home.components.HomeStatsSection
import com.calypsan.listenup.client.features.home.components.MyShelvesRow
import com.calypsan.listenup.client.presentation.home.HomeUiState
import com.calypsan.listenup.client.presentation.home.HomeViewModel
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
    onBookClick: (String) -> Unit,
    onNavigateToLibrary: () -> Unit,
    onShelfClick: (String) -> Unit,
    onSeeAllShelves: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

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
                    onRefresh = { viewModel.refresh() },
                    onBookClick = onBookClick,
                    onNavigateToLibrary = onNavigateToLibrary,
                    onShelfClick = onShelfClick,
                    onSeeAllShelves = onSeeAllShelves,
                    modifier =
                        Modifier
                            .padding(paddingValues)
                            .background(MaterialTheme.colorScheme.surfaceContainerLow),
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
    onRefresh: () -> Unit,
    onBookClick: (String) -> Unit,
    onNavigateToLibrary: () -> Unit,
    onShelfClick: (String) -> Unit,
    onSeeAllShelves: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PullToRefreshBox(
        isRefreshing = state.isLoading,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
        ) {
            HomeHeader(greeting = state.greeting)

            if (state.hasContinueListening) {
                ContinueListeningRow(
                    books = state.continueListening,
                    onBookClick = onBookClick,
                )
                Spacer(modifier = Modifier.height(16.dp))
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

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun HomeContentWide(
    state: HomeUiState.Ready,
    onShelfClick: (String) -> Unit,
    onSeeAllShelves: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        HomeStatsSection()
        if (state.hasMyShelves) {
            Spacer(modifier = Modifier.width(16.dp))
            MyShelvesRow(
                shelves = state.myShelves,
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
    HomeStatsSection()

    if (state.hasMyShelves) {
        Spacer(modifier = Modifier.height(24.dp))
        MyShelvesRow(
            shelves = state.myShelves,
            onShelfClick = onShelfClick,
            onSeeAllClick = onSeeAllShelves,
        )
    }
}
