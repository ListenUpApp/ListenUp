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
import com.calypsan.listenup.client.domain.repository.LeaderboardPeriod
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.discover_leaderboard

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
                label = "Week",
                selected = selectedPeriod == LeaderboardPeriod.WEEK,
                onClick = { onPeriodSelected(LeaderboardPeriod.WEEK) },
            )
            PeriodChip(
                label = "Month",
                selected = selectedPeriod == LeaderboardPeriod.MONTH,
                onClick = { onPeriodSelected(LeaderboardPeriod.MONTH) },
            )
            PeriodChip(
                label = "All",
                selected = selectedPeriod == LeaderboardPeriod.ALL,
                onClick = { onPeriodSelected(LeaderboardPeriod.ALL) },
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
