package com.calypsan.listenup.client.features.discover.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardPeriod
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.discover_leaderboard
import listenup.composeapp.generated.resources.discover_period_all
import listenup.composeapp.generated.resources.discover_period_month
import listenup.composeapp.generated.resources.discover_period_week

/**
 * Leaderboard header with title and period selector.
 *
 * @param selectedPeriod Currently selected period
 * @param onPeriodSelected Callback when a period is selected
 * @param modifier Modifier from parent
 */
@Composable
fun LeaderboardHeader(
    selectedPeriod: LeaderboardPeriod,
    onPeriodSelected: (LeaderboardPeriod) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(Res.string.discover_leaderboard),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        // Period chips
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PeriodChip(
                label = stringResource(Res.string.discover_period_week),
                selected = selectedPeriod == LeaderboardPeriod.Week,
                onClick = { onPeriodSelected(LeaderboardPeriod.Week) },
            )
            PeriodChip(
                label = stringResource(Res.string.discover_period_month),
                selected = selectedPeriod == LeaderboardPeriod.Month,
                onClick = { onPeriodSelected(LeaderboardPeriod.Month) },
            )
            PeriodChip(
                label = stringResource(Res.string.discover_period_all),
                selected = selectedPeriod == LeaderboardPeriod.AllTime,
                onClick = { onPeriodSelected(LeaderboardPeriod.AllTime) },
            )
        }
    }
}

@Composable
private fun PeriodChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
            )
        },
    )
}
