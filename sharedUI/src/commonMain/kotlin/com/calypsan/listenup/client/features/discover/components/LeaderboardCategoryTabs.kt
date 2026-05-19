package com.calypsan.listenup.client.features.discover.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.calypsan.listenup.client.domain.repository.LeaderboardCategory

/**
 * Category tabs for switching between leaderboard rankings.
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
    val categories =
        listOf(
            LeaderboardCategory.TIME to "Time",
            LeaderboardCategory.BOOKS to "Books",
            LeaderboardCategory.STREAK to "Streak",
        )

    val selectedIndex = categories.indexOfFirst { it.first == selectedCategory }

    PrimaryTabRow(
        selectedTabIndex = selectedIndex,
        modifier = modifier.fillMaxWidth(),
    ) {
        categories.forEachIndexed { index, (category, label) ->
            Tab(
                selected = index == selectedIndex,
                onClick = { onCategorySelected(category) },
                text = { Text(label) },
            )
        }
    }
}
