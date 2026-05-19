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
import com.calypsan.listenup.client.domain.repository.LeaderboardCategory
import com.calypsan.listenup.client.domain.repository.LeaderboardPeriod
import com.calypsan.listenup.client.presentation.discover.LeaderboardUiState
import com.calypsan.listenup.client.presentation.discover.LeaderboardViewModel
import org.koin.compose.viewmodel.koinViewModel
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.discover_loading_leaderboard
import listenup.composeapp.generated.resources.discover_start_listening_to_join_the

/**
 * Discover screen leaderboard section.
 *
 * Displays a gamified leaderboard with:
 * - Period selector (Week/Month/Year/All Time)
 * - Category tabs (Time/Books/Streak)
 * - Ranked list of users
 * - Community aggregate stats
 *
 * @param onUserClick Callback when a user is clicked (navigates to profile)
 * @param modifier Modifier from parent
 * @param viewModel LeaderboardViewModel injected via Koin
 */
@Composable
fun DiscoverLeaderboardSection(
    onUserClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LeaderboardViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (val current = state) {
                is LeaderboardUiState.Loading -> {
                    LeaderboardHeader(
                        selectedPeriod = LeaderboardPeriod.WEEK,
                        onPeriodSelected = { viewModel.selectPeriod(it) },
                    )
                    Text(
                        text = stringResource(Res.string.discover_loading_leaderboard),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is LeaderboardUiState.Error -> {
                    LeaderboardHeader(
                        selectedPeriod = LeaderboardPeriod.WEEK,
                        onPeriodSelected = { viewModel.selectPeriod(it) },
                    )
                    Text(
                        text = current.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                is LeaderboardUiState.Ready -> {
                    ReadyContent(
                        ready = current,
                        onPeriodSelected = { viewModel.selectPeriod(it) },
                        onCategorySelected = { viewModel.selectCategory(it) },
                        onUserClick = onUserClick,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReadyContent(
    ready: LeaderboardUiState.Ready,
    onPeriodSelected: (LeaderboardPeriod) -> Unit,
    onCategorySelected: (LeaderboardCategory) -> Unit,
    onUserClick: (String) -> Unit,
) {
    LeaderboardHeader(
        selectedPeriod = ready.selectedPeriod,
        onPeriodSelected = onPeriodSelected,
    )

    if (!ready.hasData) {
        Text(
            text = stringResource(Res.string.discover_start_listening_to_join_the),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    val categories =
        listOf(
            LeaderboardCategory.TIME,
            LeaderboardCategory.BOOKS,
            LeaderboardCategory.STREAK,
        )

    val pagerState =
        rememberPagerState(
            initialPage = categories.indexOf(ready.selectedCategory).coerceAtLeast(0),
            pageCount = { categories.size },
        )

    // Sync pager with tab selection.
    LaunchedEffect(ready.selectedCategory) {
        val targetPage = categories.indexOf(ready.selectedCategory)
        if (targetPage >= 0 && pagerState.currentPage != targetPage) {
            pagerState.animateScrollToPage(targetPage)
        }
    }

    // Sync tab selection with pager swipes.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            val category = categories.getOrNull(page)
            if (category != null && category != ready.selectedCategory) {
                onCategorySelected(category)
            }
        }
    }

    LeaderboardCategoryTabs(
        selectedCategory = ready.selectedCategory,
        onCategorySelected = onCategorySelected,
    )

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxWidth(),
    ) { page ->
        val category = categories[page]
        val entries = ready.entriesByCategory.getValue(category)
        LeaderboardList(
            entries = entries,
            category = category,
            onUserClick = onUserClick,
        )
    }

    if (ready.hasCommunityStats) {
        CommunityStatsRow(stats = ready.communityStats!!)
    }
}
