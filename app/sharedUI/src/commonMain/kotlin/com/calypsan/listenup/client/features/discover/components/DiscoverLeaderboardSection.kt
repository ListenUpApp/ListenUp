package com.calypsan.listenup.client.features.discover.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.ButtonGroupChoice
import com.calypsan.listenup.client.design.components.ConnectedSelectButtonGroup
import com.calypsan.listenup.client.design.components.SectionTitle
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardCategory
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardPeriod
import com.calypsan.listenup.client.presentation.discover.LeaderboardSelection
import com.calypsan.listenup.client.presentation.discover.LeaderboardUiState
import com.calypsan.listenup.client.presentation.discover.leaderboardEntries
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.discover_could_not_load_leaderboard
import listenup.composeapp.generated.resources.discover_leaderboard
import listenup.composeapp.generated.resources.discover_leaderboard_category_books
import listenup.composeapp.generated.resources.discover_leaderboard_category_group
import listenup.composeapp.generated.resources.discover_leaderboard_category_streak
import listenup.composeapp.generated.resources.discover_leaderboard_category_time
import listenup.composeapp.generated.resources.discover_loading_leaderboard
import listenup.composeapp.generated.resources.discover_start_listening_to_join_the
import org.jetbrains.compose.resources.stringResource

/**
 * Discover's leaderboard: one `surfaceContainer` card (28dp corners) holding the title, the period
 * chips ([LeaderboardPeriodChips]), the category as a connected button group, and the ranked list.
 * One control per choice: switching category is a pure state filter in the ViewModel, with no pager.
 *
 * Loading, Empty and Error keep today's copy (their redesigned states are PR 6).
 *
 * @param onUserClick Opens a person's profile.
 */
@Composable
internal fun DiscoverLeaderboardSection(
    state: LeaderboardUiState,
    selection: LeaderboardSelection,
    onSelectPeriod: (LeaderboardPeriod) -> Unit,
    onSelectCategory: (LeaderboardCategory) -> Unit,
    onUserClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.padding(start = Spacing.lg, end = Spacing.lg, top = 18.dp, bottom = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            SectionTitle(title = stringResource(Res.string.discover_leaderboard))
            LeaderboardPeriodChips(selected = selection.period, onSelect = onSelectPeriod)
            ConnectedSelectButtonGroup(
                choices = LeaderboardCategory.entries.map { ButtonGroupChoice(it, categoryLabel(it)) },
                selected = selection.category,
                onSelect = onSelectCategory,
                groupLabel = stringResource(Res.string.discover_leaderboard_category_group),
            )
            when (state) {
                is LeaderboardUiState.Loading -> {
                    Text(
                        text = stringResource(Res.string.discover_loading_leaderboard),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is LeaderboardUiState.Empty -> {
                    Text(
                        text = stringResource(Res.string.discover_start_listening_to_join_the),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is LeaderboardUiState.Error -> {
                    Text(
                        text = stringResource(Res.string.discover_could_not_load_leaderboard),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                is LeaderboardUiState.Data -> {
                    LeaderboardList(
                        entries = leaderboardEntries(state.snapshot, selection.category),
                        category = selection.category,
                        onUserClick = onUserClick,
                    )
                }
            }
        }
    }
}

@Composable
private fun categoryLabel(category: LeaderboardCategory): String =
    when (category) {
        LeaderboardCategory.Time -> stringResource(Res.string.discover_leaderboard_category_time)
        LeaderboardCategory.Books -> stringResource(Res.string.discover_leaderboard_category_books)
        LeaderboardCategory.Streak -> stringResource(Res.string.discover_leaderboard_category_streak)
    }
