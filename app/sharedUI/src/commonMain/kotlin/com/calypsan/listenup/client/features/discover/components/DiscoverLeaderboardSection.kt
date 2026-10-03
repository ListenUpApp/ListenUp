package com.calypsan.listenup.client.features.discover.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardCategory
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardPeriod
import com.calypsan.listenup.client.presentation.discover.LeaderboardUiState
import com.calypsan.listenup.client.presentation.discover.LeaderboardViewModel
import com.calypsan.listenup.client.presentation.discover.leaderboardEntries
import org.koin.compose.viewmodel.koinViewModel
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.discover_could_not_load_leaderboard
import listenup.composeapp.generated.resources.discover_loading_leaderboard
import listenup.composeapp.generated.resources.discover_start_listening_to_join_the

/**
 * Discover screen leaderboard section.
 *
 * Renders a sealed [LeaderboardUiState] from [LeaderboardViewModel]:
 * The title and the period selector ([LeaderboardHeader]) sit above the board's card, at the screen
 * margins; the card holds the board for the state:
 * - [LeaderboardUiState.Loading] — a loading placeholder.
 * - [LeaderboardUiState.Empty] — an empty-state message.
 * - [LeaderboardUiState.Data] — the full ranked list with category pager.
 * - [LeaderboardUiState.Error] — an error message.
 *
 * Switching categories is a pure state filter in the ViewModel — no DB re-query.
 *
 * @param onUserClick Callback when a user row is clicked (navigates to profile).
 * @param modifier Modifier from parent.
 * @param viewModel LeaderboardViewModel injected via Koin.
 */
@Composable
fun DiscoverLeaderboardSection(
    onUserClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LeaderboardViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val selectedPeriod = (state as? LeaderboardUiState.Data)?.period ?: LeaderboardPeriod.Week

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // The title and period selector sit above the card, at the screen margins: inside the card's
        // padding, "12 months" has no room on a phone.
        LeaderboardHeader(
            selectedPeriod = selectedPeriod,
            onPeriodSelected = viewModel::selectPeriod,
        )
        LeaderboardCard(state = state, onCategorySelected = viewModel::selectCategory, onUserClick = onUserClick)
    }
}

@Composable
private fun LeaderboardCard(
    state: LeaderboardUiState,
    onCategorySelected: (LeaderboardCategory) -> Unit,
    onUserClick: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (val current = state) {
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

                is LeaderboardUiState.Data -> {
                    DataContent(
                        data = current,
                        onCategorySelected = onCategorySelected,
                        onUserClick = onUserClick,
                    )
                }

                is LeaderboardUiState.Error -> {
                    Text(
                        text = stringResource(Res.string.discover_could_not_load_leaderboard),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun DataContent(
    data: LeaderboardUiState.Data,
    onCategorySelected: (LeaderboardCategory) -> Unit,
    onUserClick: (String) -> Unit,
) {
    val categories = LeaderboardCategory.entries
    val pagerState =
        rememberPagerState(
            initialPage = categories.indexOf(data.category).coerceAtLeast(0),
            pageCount = { categories.size },
        )

    // Sync pager page to category state.
    LaunchedEffect(data.category) {
        val targetPage = categories.indexOf(data.category)
        if (targetPage >= 0 && pagerState.currentPage != targetPage) {
            pagerState.animateScrollToPage(targetPage)
        }
    }

    // Sync category state to pager swipes.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            val category = categories.getOrNull(page)
            if (category != null && category != data.category) {
                onCategorySelected(category)
            }
        }
    }

    LeaderboardCategoryTabs(
        selectedCategory = data.category,
        onCategorySelected = onCategorySelected,
    )

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxWidth(),
    ) { page ->
        val category = categories[page]
        // `category` (this page's), NOT `data.category` (the selected one): the pager renders all
        // three boards side by side, and reading the selection here would show the same list on
        // every page while the tabs still moved.
        val entries = leaderboardEntries(data.snapshot, category)
        LeaderboardList(
            entries = entries,
            category = category,
            onUserClick = onUserClick,
        )
    }
}
