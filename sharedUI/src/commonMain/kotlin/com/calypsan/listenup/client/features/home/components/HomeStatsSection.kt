package com.calypsan.listenup.client.features.home.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.domain.DayBucket
import com.calypsan.listenup.client.presentation.home.HomeStatsUiState
import com.calypsan.listenup.client.presentation.home.HomeStatsViewModel
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_loading_item
import listenup.composeapp.generated.resources.home_this_week
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * Home "This week" stats card — listening total + 7-day chart, with streak and top-genre breakdown.
 *
 * Adaptive: on wide windows the chart sits beside the streak/genres column (vertical divider); on
 * compact they stack (horizontal divider) — mirroring the design's `StatsCard` / `StatsCard wide`.
 *
 * @param isWide Whether the window is medium+ (drives the two-column vs stacked layout).
 * @param modifier Modifier from parent.
 * @param viewModel HomeStatsViewModel injected via Koin.
 */
@Composable
fun HomeStatsSection(
    isWide: Boolean,
    modifier: Modifier = Modifier,
    viewModel: HomeStatsViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(if (isWide) 28.dp else 22.dp)) {
            when (val s = state) {
                HomeStatsUiState.Loading -> {
                    StatsPlaceholder(stringResource(Res.string.common_loading_item, "stats"))
                }

                HomeStatsUiState.Empty -> {
                    // Render the card at zero (chart + "0m") rather than a placeholder; streak and
                    // genres collapse until there's data.
                    HomeStatsContent(state = EmptyWeekStats, isWide = isWide)
                }

                is HomeStatsUiState.Data -> {
                    HomeStatsContent(state = s, isWide = isWide)
                }

                is HomeStatsUiState.Error -> {
                    StatsPlaceholder("Couldn't load stats.", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun StatsPlaceholder(
    message: String,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Overline(stringResource(Res.string.home_this_week))
    Spacer(Modifier.height(12.dp))
    Text(text = message, style = MaterialTheme.typography.bodyMedium, color = color)
}

@Composable
internal fun HomeStatsContent(
    state: HomeStatsUiState.Data,
    isWide: Boolean,
) {
    val totalSeconds = state.dailyBuckets.sumOf { it.totalSeconds }
    val hours = totalSeconds / 3600
    val minutes = totalSeconds % 3600 / 60

    val chartColumn: @Composable () -> Unit = {
        Column {
            Overline(stringResource(Res.string.home_this_week))
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "${hours}h ${minutes}m",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-1.5).sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "listened",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            Spacer(Modifier.height(18.dp))
            if (state.dailyBuckets.isNotEmpty()) {
                DailyListeningChart(dailyBuckets = state.dailyBuckets, modifier = Modifier.fillMaxWidth())
            }
        }
    }

    val detailColumn: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            if (state.hasStreak) {
                StreakIndicator(currentStreak = state.currentStreakDays, longestStreak = state.longestStreakDays)
            }
            if (state.hasGenreData) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Overline("Top genres")
                    GenreBreakdownBars(genres = state.topGenres)
                }
            }
        }
    }

    val hasDetail = state.hasStreak || state.hasGenreData
    when {
        // No streak/genres yet — show only the chart column (collapse the detail side).
        !hasDetail -> {
            chartColumn()
        }

        isWide -> {
            Row {
                Box(Modifier.weight(1.3f)) { chartColumn() }
                VerticalDivider(modifier = Modifier.padding(horizontal = 24.dp))
                Box(Modifier.weight(1f)) { detailColumn() }
            }
        }

        else -> {
            Column {
                chartColumn()
                HorizontalDivider(modifier = Modifier.padding(vertical = 20.dp))
                detailColumn()
            }
        }
    }
}

/** A zeroed week used for the empty stats state — renders the chart at 0 with no streak/genres. */
private val EmptyWeekStats =
    HomeStatsUiState.Data(
        totalSecondsThisWeek = 0L,
        currentStreakDays = 0,
        longestStreakDays = 0,
        dailyBuckets = List(7) { DayBucket(dayOffsetFromToday = it, totalSeconds = 0L) },
        topGenres = emptyList(),
    )

/** Small uppercase overline label used inside the stats card. */
@Composable
private fun Overline(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
