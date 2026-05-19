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
 * Shows skeleton loading state while data is being fetched.
 * Shows error message when the stats flow fails.
 * Shows empty-state text when there's no data yet.
 *
 * @param modifier Modifier from parent
 * @param viewModel HomeStatsViewModel injected via Koin
 */
@Composable
fun HomeStatsSection(
    modifier: Modifier = Modifier,
    viewModel: HomeStatsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

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
            // Section title
            Text(
                text = stringResource(Res.string.home_this_week),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            when (val s = state) {
                is HomeStatsUiState.Loading -> {
                    Text(
                        text = stringResource(Res.string.common_loading_item, "stats"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is HomeStatsUiState.Error -> {
                    Text(
                        text = s.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                is HomeStatsUiState.Ready -> {
                    if (s.hasData) {
                        HomeStatsContent(state = s)
                    } else {
                        Text(
                            text = stringResource(Res.string.home_start_listening_to_see_your),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Stats content when data is available.
 */
@Composable
private fun HomeStatsContent(state: HomeStatsUiState.Ready) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 7-day listening chart
        if (state.dailyListening.isNotEmpty()) {
            DailyListeningChart(
                dailyListening = state.dailyListening,
                maxListenTimeMs = state.maxDailyListenTimeMs,
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
                genres = state.genreBreakdown,
            )
        }
    }
}
