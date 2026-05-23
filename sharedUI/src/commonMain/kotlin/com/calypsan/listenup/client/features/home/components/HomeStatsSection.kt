package com.calypsan.listenup.client.features.home.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.presentation.home.HomeStatsUiState
import com.calypsan.listenup.client.presentation.home.HomeStatsViewModel
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_loading_item
import listenup.composeapp.generated.resources.home_start_listening_to_see_your
import listenup.composeapp.generated.resources.home_this_week
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * Home screen stats section.
 *
 * Displays a card with the user's listening stats:
 * - 7-day bar chart showing daily listening time
 * - Current streak indicator with fire emoji
 * - Top 3 genres breakdown
 *
 * Renders one of four states driven by [HomeStatsUiState]:
 * - [HomeStatsUiState.Loading]: skeleton placeholder while Room query loads
 * - [HomeStatsUiState.Empty]: friendly prompt before any listening history exists
 * - [HomeStatsUiState.Data]: populated chart + streak + genres
 * - [HomeStatsUiState.Error]: error message (retry is implicit — stateIn resubscribes)
 *
 * @param modifier Modifier from parent
 * @param viewModel HomeStatsViewModel injected via Koin
 */
@Composable
fun HomeStatsSection(
    modifier: Modifier = Modifier,
    viewModel: HomeStatsViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Card(
        modifier =
            modifier
                .widthIn(max = 600.dp)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Section title — always visible regardless of state
            Text(
                text = stringResource(Res.string.home_this_week),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            when (val s = state) {
                HomeStatsUiState.Loading -> {
                    Text(
                        text = stringResource(Res.string.common_loading_item, "stats"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                HomeStatsUiState.Empty -> {
                    Text(
                        text = stringResource(Res.string.home_start_listening_to_see_your),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is HomeStatsUiState.Data -> {
                    HomeStatsContent(state = s)
                }

                is HomeStatsUiState.Error -> {
                    Text(
                        text = "Couldn't load stats.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/**
 * Stats content when data is available and the user has listening history.
 */
@Composable
private fun HomeStatsContent(state: HomeStatsUiState.Data) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 7-day listening chart
        if (state.dailyBuckets.isNotEmpty()) {
            DailyListeningChart(
                dailyBuckets = state.dailyBuckets,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Streak indicator
        if (state.hasStreak) {
            StreakIndicator(
                currentStreak = state.currentStreakDays,
                longestStreak = state.longestStreakDays,
            )
        }

        // Genre breakdown
        if (state.hasGenreData) {
            GenreBreakdownBars(
                genres = state.topGenres,
            )
        }
    }
}
