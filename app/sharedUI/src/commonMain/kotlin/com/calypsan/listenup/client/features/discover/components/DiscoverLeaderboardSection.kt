package com.calypsan.listenup.client.features.discover.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.ButtonGroupChoice
import com.calypsan.listenup.client.design.components.ConnectedSelectButtonGroup
import com.calypsan.listenup.client.design.components.InlineRetry
import com.calypsan.listenup.client.design.components.RowsSkeleton
import com.calypsan.listenup.client.design.components.SectionTitle
import androidx.compose.ui.platform.testTag
import com.calypsan.listenup.client.presentation.discover.CommunitySize
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardCategory
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardPeriod
import com.calypsan.listenup.client.presentation.discover.LeaderboardSelection
import com.calypsan.listenup.client.presentation.discover.LeaderboardUiState
import com.calypsan.listenup.client.presentation.discover.leaderboardEntries
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.discover_error_leaderboard
import listenup.composeapp.generated.resources.discover_leaderboard_empty_period
import listenup.composeapp.generated.resources.discover_leaderboard_just_you
import listenup.composeapp.generated.resources.discover_leaderboard
import listenup.composeapp.generated.resources.discover_leaderboard_category_books
import listenup.composeapp.generated.resources.discover_leaderboard_category_group
import listenup.composeapp.generated.resources.discover_leaderboard_category_streak
import listenup.composeapp.generated.resources.discover_leaderboard_category_time
import org.jetbrains.compose.resources.stringResource

/**
 * Discover's leaderboard: one `surfaceContainer` card (28dp corners) holding the title, the period
 * chips ([LeaderboardPeriodChips]), the category as a connected button group, and the ranked list.
 * One control per choice: switching category is a pure state filter in the ViewModel, with no pager.
 *
 * The period chips stay in every state (spec §3.8). Loading is a skeleton of rows; a failure is a line and Try
 * again; an empty period says so, or "Just you so far" when you are alone, when the category also goes.
 *
 * @param onUserClick Opens a person's profile.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun DiscoverLeaderboardSection(
    state: LeaderboardUiState,
    selection: LeaderboardSelection,
    community: CommunitySize,
    onSelectPeriod: (LeaderboardPeriod) -> Unit,
    onSelectCategory: (LeaderboardCategory) -> Unit,
    onUserClick: (String) -> Unit,
    onRetry: () -> Unit,
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
            // Only you: all three categories would rank you first, so the choice goes (spec §3.8).
            if (community != CommunitySize.JustYou) {
                ConnectedSelectButtonGroup(
                    choices = LeaderboardCategory.entries.map { ButtonGroupChoice(it, categoryLabel(it)) },
                    selected = selection.category,
                    onSelect = onSelectCategory,
                    groupLabel = stringResource(Res.string.discover_leaderboard_category_group),
                    // The card is surfaceContainer, the group's default unselected colour: lift the
                    // unselected segments a tone so each one shows (board AndroidPhone).
                    colors =
                        ToggleButtonDefaults.toggleButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ),
                )
            }
            when (state) {
                is LeaderboardUiState.Loading -> {
                    LeaderboardSkeleton()
                }

                is LeaderboardUiState.Error -> {
                    InlineRetry(line = stringResource(Res.string.discover_error_leaderboard), onRetry = onRetry)
                }

                is LeaderboardUiState.Empty -> {
                    when (community) {
                        // Never say "nobody" or "just you" before the roster has synced (plan C4).
                        CommunitySize.Unknown -> LeaderboardSkeleton()
                        CommunitySize.JustYou -> QuietLine(stringResource(Res.string.discover_leaderboard_just_you))
                        CommunitySize.Others -> QuietLine(stringResource(Res.string.discover_leaderboard_empty_period))
                    }
                }

                is LeaderboardUiState.Data -> {
                    LeaderboardList(
                        entries = leaderboardEntries(state.snapshot, selection.category),
                        category = selection.category,
                        onUserClick = onUserClick,
                    )
                    if (community == CommunitySize.JustYou) {
                        QuietLine(stringResource(Res.string.discover_leaderboard_just_you))
                    }
                }
            }
        }
    }
}

@Composable
private fun LeaderboardSkeleton() {
    RowsSkeleton(rows = 5, leadingSize = 40.dp, modifier = Modifier.testTag("leaderboard-skeleton"))
}

@Composable
private fun QuietLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun categoryLabel(category: LeaderboardCategory): String =
    when (category) {
        LeaderboardCategory.Time -> stringResource(Res.string.discover_leaderboard_category_time)
        LeaderboardCategory.Books -> stringResource(Res.string.discover_leaderboard_category_books)
        LeaderboardCategory.Streak -> stringResource(Res.string.discover_leaderboard_category_streak)
    }
