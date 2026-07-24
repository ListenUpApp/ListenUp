package com.calypsan.listenup.client.features.discover.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardCategory
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.discover_leaderboard_category_books
import listenup.composeapp.generated.resources.discover_leaderboard_category_streak
import listenup.composeapp.generated.resources.discover_leaderboard_category_time

/**
 * Category tabs for switching between leaderboard rankings — the M3 [PrimaryTabRow] underline tabs
 * (Time / Books / Streak) matching the mockup, with the active label emphasized in the brand colour.
 *
 * @param selectedCategory Currently selected category
 * @param onCategorySelected Callback when a category is selected
 * @param modifier Modifier from parent
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeaderboardCategoryTabs(
    selectedCategory: LeaderboardCategory,
    onCategorySelected: (LeaderboardCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    val categories = LeaderboardCategory.entries
    val selectedIndex = categories.indexOf(selectedCategory).coerceAtLeast(0)

    PrimaryTabRow(
        selectedTabIndex = selectedIndex,
        modifier = modifier.fillMaxWidth(),
    ) {
        categories.forEachIndexed { index, category ->
            val selected = index == selectedIndex
            Tab(
                selected = selected,
                onClick = { onCategorySelected(category) },
                text = {
                    Text(
                        text = categoryLabel(category),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
                    )
                },
            )
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
