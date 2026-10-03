package com.calypsan.listenup.client.features.discover.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.ButtonGroupChoice
import com.calypsan.listenup.client.design.components.ConnectedSelectButtonGroup
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardPeriod
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.discover_leaderboard
import listenup.composeapp.generated.resources.discover_leaderboard_period_12_months
import listenup.composeapp.generated.resources.discover_leaderboard_period_12_months_description
import listenup.composeapp.generated.resources.discover_leaderboard_period_30_days
import listenup.composeapp.generated.resources.discover_leaderboard_period_30_days_description
import listenup.composeapp.generated.resources.discover_leaderboard_period_7_days
import listenup.composeapp.generated.resources.discover_leaderboard_period_7_days_description
import listenup.composeapp.generated.resources.discover_leaderboard_period_all_time
import listenup.composeapp.generated.resources.discover_leaderboard_period_group
import org.jetbrains.compose.resources.stringResource

/**
 * Leaderboard header: the section title above the period selector — a Material 3 Expressive connected
 * button group ([ConnectedSelectButtonGroup]) offering 7 days, 30 days, 12 months and All time, in the
 * same order as iOS and web. It sits on its own row at the screen margins, outside the board's card, so
 * all four labels get their full width on a 360dp phone; at a large font scale the group wraps into
 * two pairs. The periods are trailing windows, so the labels name the window rather than a calendar
 * unit, and a screen reader hears the full "Last 7 days".
 *
 * @param selectedPeriod Currently selected period
 * @param onPeriodSelected Callback when a period is selected
 * @param modifier Modifier from parent
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LeaderboardHeader(
    selectedPeriod: LeaderboardPeriod,
    onPeriodSelected: (LeaderboardPeriod) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(Res.string.discover_leaderboard),
            style = MaterialTheme.typography.titleLargeEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )

        ConnectedSelectButtonGroup(
            choices = periodChoices(),
            selected = selectedPeriod,
            onSelect = onPeriodSelected,
            groupLabel = stringResource(Res.string.discover_leaderboard_period_group),
        )
    }
}

@Composable
private fun periodChoices(): List<ButtonGroupChoice<LeaderboardPeriod>> =
    listOf(
        ButtonGroupChoice(
            value = LeaderboardPeriod.Week,
            label = stringResource(Res.string.discover_leaderboard_period_7_days),
            accessibleLabel = stringResource(Res.string.discover_leaderboard_period_7_days_description),
        ),
        ButtonGroupChoice(
            value = LeaderboardPeriod.Month,
            label = stringResource(Res.string.discover_leaderboard_period_30_days),
            accessibleLabel = stringResource(Res.string.discover_leaderboard_period_30_days_description),
        ),
        ButtonGroupChoice(
            value = LeaderboardPeriod.Year,
            label = stringResource(Res.string.discover_leaderboard_period_12_months),
            accessibleLabel = stringResource(Res.string.discover_leaderboard_period_12_months_description),
        ),
        ButtonGroupChoice(
            value = LeaderboardPeriod.AllTime,
            label = stringResource(Res.string.discover_leaderboard_period_all_time),
        ),
    )
