package com.calypsan.listenup.client.features.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.features.home.components.DailyListeningChart
import com.calypsan.listenup.client.features.home.components.GenreBreakdownBars
import com.calypsan.listenup.client.presentation.home.HomeStatsUiState
import com.calypsan.listenup.client.presentation.home.weekChartColumns
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_try_again
import listenup.composeapp.generated.resources.home_best_streak_days
import listenup.composeapp.generated.resources.home_best_streak_one_day
import listenup.composeapp.generated.resources.home_chart_a11y
import listenup.composeapp.generated.resources.home_chart_today_a11y
import listenup.composeapp.generated.resources.home_couldnt_load_stats
import listenup.composeapp.generated.resources.home_hours_minutes
import listenup.composeapp.generated.resources.home_leaderboard
import listenup.composeapp.generated.resources.home_minutes
import listenup.composeapp.generated.resources.home_start_listening_to_see_your
import listenup.composeapp.generated.resources.home_streak_chip
import listenup.composeapp.generated.resources.home_top_genres
import listenup.composeapp.generated.resources.home_your_last_7_days
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.seconds

private const val SECONDS_PER_HOUR = 3_600L
private const val SECONDS_PER_MINUTE = 60L
private val PhoneChartHeight = 120.dp
private val WideChartHeight = 110.dp
private val MinTouchTarget = 48.dp
private val StreakChipHeight = 40.dp

/**
 * "Your last 7 days" (spec §2.6). Stateless: the screen hands it [stats] from `HomeStatsViewModel` (C12). Loading,
 * Empty and Error keep minimal content until Home PR 4 designs them (C16).
 */
@Composable
internal fun HomeStatsCard(
    stats: HomeStatsUiState,
    isWide: Boolean,
    onOpenLeaderboard: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
            when (stats) {
                HomeStatsUiState.Loading -> {
                    StatsHeading()
                }

                HomeStatsUiState.Empty -> {
                    StatsHeading()
                    Text(
                        text = stringResource(Res.string.home_start_listening_to_see_your),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is HomeStatsUiState.Error -> {
                    StatsHeading()
                    Text(
                        text = stringResource(Res.string.home_couldnt_load_stats),
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (stats.isRetryable) {
                        TextButton(onClick = onRetry) { Text(stringResource(Res.string.common_try_again)) }
                    }
                }

                is HomeStatsUiState.Data -> {
                    StatsData(stats, isWide, onOpenLeaderboard)
                }
            }
        }
    }
}

@Composable
private fun StatsHeading(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(Res.string.home_your_last_7_days),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.semantics { heading() },
    )
}

@Composable
private fun StatsData(
    stats: HomeStatsUiState.Data,
    isWide: Boolean,
    onOpenLeaderboard: () -> Unit,
) {
    val streak = stats.streak
    val totalSeconds = stats.dailyBuckets.sumOf { it.totalSeconds }
    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            StatsHeading()
            Text(
                text = weekTotal(totalSeconds),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        streak.chipDays?.let { days -> StreakChip(days) }
    }
    val chartLabel = chartDescription(stats)
    DailyListeningChart(
        dailyBuckets = stats.dailyBuckets,
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = chartLabel },
        chartHeight = if (isWide) WideChartHeight else PhoneChartHeight,
    )
    if (stats.hasGenreData) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(
                text = stringResource(Res.string.home_top_genres),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            GenreBreakdownBars(genres = stats.topGenres)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    StatsFooter(bestDays = streak.bestDays, onOpenLeaderboard = onOpenLeaderboard)
}

/** "Best streak 12 days" when there has been one, and the Leaderboard link (PR 5 adds Discover's Week focus). */
@Composable
private fun StatsFooter(
    bestDays: Int?,
    onOpenLeaderboard: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text =
                when (bestDays) {
                    null -> ""
                    1 -> stringResource(Res.string.home_best_streak_one_day)
                    else -> stringResource(Res.string.home_best_streak_days, bestDays)
                },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onOpenLeaderboard, modifier = Modifier.heightIn(min = MinTouchTarget)) {
            Text(stringResource(Res.string.home_leaderboard))
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
        }
    }
}

/** "8h 12m", or "40m" for a week under an hour — never "0h 40m". */
@Composable
private fun weekTotal(totalSeconds: Long): String {
    val hours = (totalSeconds / SECONDS_PER_HOUR).toInt()
    val minutes = (totalSeconds % SECONDS_PER_HOUR / SECONDS_PER_MINUTE).toInt()
    return if (hours == 0) {
        stringResource(Res.string.home_minutes, minutes)
    } else {
        stringResource(Res.string.home_hours_minutes, hours, minutes)
    }
}

/** The gold chip (§2.6): `tertiaryContainer`, read as its words ("5-day streak"). */
@Composable
private fun StreakChip(days: Int) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = CircleShape,
    ) {
        Row(
            Modifier.heightIn(min = StreakChipHeight).padding(start = Spacing.sm, end = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Icon(Icons.Outlined.LocalFireDepartment, contentDescription = null)
            Text(stringResource(Res.string.home_streak_chip, days), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** "Listening per day, oldest to today: 1h 10m, …, today 1h 20m" (C15), from the same columns the chart draws. */
@Composable
private fun chartDescription(stats: HomeStatsUiState.Data): String {
    val parts =
        weekChartColumns(stats.dailyBuckets).map { column ->
            val amount = DurationFormatter.hoursMinutes(column.totalSeconds.seconds)
            if (column.isToday) stringResource(Res.string.home_chart_today_a11y, amount) else amount
        }
    return stringResource(Res.string.home_chart_a11y, parts.joinToString(", "))
}
