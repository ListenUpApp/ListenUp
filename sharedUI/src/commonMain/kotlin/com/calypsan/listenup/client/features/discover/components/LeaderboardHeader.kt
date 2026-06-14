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
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardPeriod
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.discover_leaderboard
import listenup.composeapp.generated.resources.discover_leaderboard_period_all
import listenup.composeapp.generated.resources.discover_leaderboard_period_month
import listenup.composeapp.generated.resources.discover_leaderboard_period_week

private val PERIODS =
    listOf(
        LeaderboardPeriod.Week,
        LeaderboardPeriod.Month,
        LeaderboardPeriod.AllTime,
    )

/**
 * Leaderboard header: an emphasized title above a Week / Month / All period selector built from the
 * M3 [SingleChoiceSegmentedButtonRow] — a compact segmented control matching the mockup that stays
 * legible at compact width (where three full pills alongside the title would overflow).
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
                SegmentedButton(
                    selected = selectedPeriod == period,
                    onClick = { onPeriodSelected(period) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = PERIODS.size),
                ) {
                    Text(text = periodLabel(period))
                }
            }
        }
    }
}

@Composable
private fun periodLabel(period: LeaderboardPeriod): String =
    when (period) {
        LeaderboardPeriod.Week -> stringResource(Res.string.discover_leaderboard_period_week)

        LeaderboardPeriod.Month -> stringResource(Res.string.discover_leaderboard_period_month)

        // Only Week/Month/AllTime are exposed in the selector (PERIODS); Year is unreachable here but
        // the sealed `when` must be exhaustive, so it shares the "All" label.
        LeaderboardPeriod.Year,
        LeaderboardPeriod.AllTime,
        -> stringResource(Res.string.discover_leaderboard_period_all)
    }
