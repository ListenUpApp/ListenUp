package com.calypsan.listenup.client.features.discover.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardPeriod
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.discover_leaderboard_period_all_time
import listenup.composeapp.generated.resources.discover_leaderboard_period_group
import listenup.composeapp.generated.resources.discover_leaderboard_period_month
import listenup.composeapp.generated.resources.discover_leaderboard_period_week
import listenup.composeapp.generated.resources.discover_leaderboard_period_year
import org.jetbrains.compose.resources.stringResource

/**
 * The leaderboard's period, always visible (F4): four single-select M3 filter chips in a selectable
 * group, each a radio button to TalkBack. A [FlowRow], so a large font wraps them instead of clipping.
 */
@Composable
internal fun LeaderboardPeriodChips(
    selected: LeaderboardPeriod,
    onSelect: (LeaderboardPeriod) -> Unit,
    modifier: Modifier = Modifier,
) {
    val groupLabel = stringResource(Res.string.discover_leaderboard_period_group)
    FlowRow(
        modifier = modifier.selectableGroup().semantics { contentDescription = groupLabel },
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        periodOptions().forEach { (period, label) ->
            val isSelected = period == selected
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(period) },
                label = { Text(label) },
                leadingIcon =
                    if (isSelected) {
                        {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(FilterChipDefaults.IconSize),
                            )
                        }
                    } else {
                        null
                    },
                modifier = Modifier.semantics { role = Role.RadioButton },
            )
        }
    }
}

@Composable
private fun periodOptions(): List<Pair<LeaderboardPeriod, String>> =
    listOf(
        LeaderboardPeriod.Week to stringResource(Res.string.discover_leaderboard_period_week),
        LeaderboardPeriod.Month to stringResource(Res.string.discover_leaderboard_period_month),
        LeaderboardPeriod.Year to stringResource(Res.string.discover_leaderboard_period_year),
        LeaderboardPeriod.AllTime to stringResource(Res.string.discover_leaderboard_period_all_time),
    )
