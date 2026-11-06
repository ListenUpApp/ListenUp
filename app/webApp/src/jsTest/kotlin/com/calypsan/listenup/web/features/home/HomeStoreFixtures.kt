package com.calypsan.listenup.web.features.home

import com.calypsan.listenup.client.presentation.home.HomeStatsUiState
import com.calypsan.listenup.client.presentation.home.HomeUiState
import kotlinx.coroutines.flow.MutableStateFlow

/** A session over states that never change — the shape specs use in place of the graph. */
fun fixedHome(
    state: HomeUiState,
    stats: HomeStatsUiState = HomeStatsUiState.Loading,
    onRetry: () -> Unit = {},
    onRetryStats: () -> Unit = {},
    onDismissFinished: (String) -> Unit = {},
    onRateFinished: (String, Int) -> Unit = { _, _ -> },
    onRetryConnection: () -> Unit = {},
): OpenHome =
    {
        HomeSession(
            state = MutableStateFlow(state),
            stats = MutableStateFlow(stats),
            onRetry = onRetry,
            onRetryStats = onRetryStats,
            onDismissFinished = onDismissFinished,
            onRateFinished = onRateFinished,
            onRetryConnection = onRetryConnection,
            close = {},
        )
    }
