package com.calypsan.listenup.client.features.discover.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardPeriod
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.discover_leaderboard
import listenup.composeapp.generated.resources.discover_leaderboard_period_12_months
import listenup.composeapp.generated.resources.discover_leaderboard_period_12_months_description
import listenup.composeapp.generated.resources.discover_leaderboard_period_30_days
import listenup.composeapp.generated.resources.discover_leaderboard_period_30_days_description
import listenup.composeapp.generated.resources.discover_leaderboard_period_7_days
import listenup.composeapp.generated.resources.discover_leaderboard_period_7_days_description
import listenup.composeapp.generated.resources.discover_leaderboard_period_all_time
import org.jetbrains.compose.resources.StringResource

private val PERIODS =
    listOf(
        LeaderboardPeriod.Week,
        LeaderboardPeriod.Month,
        LeaderboardPeriod.AllTime,
    )

/**
 * Leaderboard header: an emphasized title above a 7 days / 30 days / All time period selector built
 * from the M3 [SingleChoiceSegmentedButtonRow] — a compact segmented control that stays legible at
 * compact width. The periods are trailing windows, so the labels name the window rather than a calendar
 * unit, and a screen reader hears the full "Last 7 days".
 *
 * 12 months ([LeaderboardPeriod.Year]) is not offered here yet: inside the Discover card a fourth
 * segment leaves "12 months" ~57dp of its ~69dp even on a 412dp phone, so it clips. Fitting it needs a
 * different control or label, which is a design call rather than a code one.
 *
 * @param selectedPeriod Currently selected period
 * @param onPeriodSelected Callback when a period is selected
 * @param modifier Modifier from parent
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeaderboardHeader(
    selectedPeriod: LeaderboardPeriod,
    onPeriodSelected: (LeaderboardPeriod) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(Res.string.discover_leaderboard),
            style = MaterialTheme.typography.titleLargeEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
        )

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            PERIODS.forEachIndexed { index, period ->
                val description = periodDescription(period)
                SegmentedButton(
                    selected = selectedPeriod == period,
                    onClick = { onPeriodSelected(period) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = PERIODS.size),
                    modifier = Modifier.semantics { contentDescription = description },
                ) {
                    Text(text = stringResource(periodLabel(period)), maxLines = 1)
                }
            }
        }
    }
}

private fun periodLabel(period: LeaderboardPeriod): StringResource =
    when (period) {
        LeaderboardPeriod.Week -> Res.string.discover_leaderboard_period_7_days
        LeaderboardPeriod.Month -> Res.string.discover_leaderboard_period_30_days
        LeaderboardPeriod.Year -> Res.string.discover_leaderboard_period_12_months
        LeaderboardPeriod.AllTime -> Res.string.discover_leaderboard_period_all_time
    }

@Composable
private fun periodDescription(period: LeaderboardPeriod): String =
    when (period) {
        LeaderboardPeriod.Week -> stringResource(Res.string.discover_leaderboard_period_7_days_description)
        LeaderboardPeriod.Month -> stringResource(Res.string.discover_leaderboard_period_30_days_description)
        LeaderboardPeriod.Year -> stringResource(Res.string.discover_leaderboard_period_12_months_description)
        LeaderboardPeriod.AllTime -> stringResource(Res.string.discover_leaderboard_period_all_time)
    }
