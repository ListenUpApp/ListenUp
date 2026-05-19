package com.calypsan.listenup.client.features.library.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.AlphabetIndex
import com.calypsan.listenup.client.design.components.AlphabetScrollbar
import com.calypsan.listenup.client.design.components.SortSplitButton
import com.calypsan.listenup.client.domain.model.SeriesWithBooks
import com.calypsan.listenup.client.presentation.library.SortCategory
import com.calypsan.listenup.client.presentation.library.SortState
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_no_items_yet
import listenup.composeapp.generated.resources.library_empty_tab_description

/**
 * Content for the Series tab in the Library screen.
 *
 * Displays a vertical list of series cards with animated cover stacks.
 * Each card shows overlapping book covers that cycle through all books
 * in the series, with the series name and book count below.
 *
 * @param series List of series with their books
 * @param sortState Current sort state (category + direction)
 * @param onCategorySelected Called when user selects a new category
 * @param onDirectionToggle Called when user toggles sort direction
 * @param onSeriesClick Callback when a series is clicked (passes series ID)
 * @param modifier Optional modifier
 */
@Composable
fun SeriesContent(
    series: List<SeriesWithBooks>,
    sortState: SortState,
    onCategorySelected: (SortCategory) -> Unit,
    onDirectionToggle: () -> Unit,
    onSeriesClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        if (series.isEmpty()) {
            SeriesEmptyState()
        } else {
            val gridState = rememberLazyGridState()
            val scope = rememberCoroutineScope()

            // Build alphabet index for name-based sort
            val alphabetIndex =
                remember(series, sortState) {
                    if (sortState.category == SortCategory.NAME) {
                        AlphabetIndex.build(series) { it.series.name }
                    } else {
                        null
                    }
                }

            val isScrolling by remember {
                derivedStateOf { gridState.isScrollInProgress }
            }

            // Track scroll direction for button visibility
            var previousScrollOffset by remember { mutableIntStateOf(0) }
            val showSortButton by remember {
                derivedStateOf {
                    val firstVisible = gridState.firstVisibleItemIndex
                    val currentOffset = gridState.firstVisibleItemScrollOffset

                    val isAtTop = firstVisible == 0 && currentOffset < 50
                    val isScrollingUp = currentOffset < previousScrollOffset

                    previousScrollOffset = currentOffset
                    isAtTop || isScrollingUp || !gridState.isScrollInProgress
                }
            }

            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(minSize = 200.dp),
                contentPadding =
                    PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 48.dp,
                        bottom = 16.dp,
                    ),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(
                    items = series,
                    key = { it.series.id.value },
                ) { seriesWithBooks ->
                    SeriesCard(
                        seriesWithBooks = seriesWithBooks,
                        onClick = { onSeriesClick(seriesWithBooks.series.id.value) },
                    )
                }
            }

            // Sort split button
            SortSplitButton(
                state = sortState,
                categories = SortCategory.seriesCategories,
                onCategorySelected = onCategorySelected,
                onDirectionToggle = onDirectionToggle,
                visible = showSortButton,
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 16.dp, top = 8.dp),
            )

            // Alphabet scrollbar (only for name sort)
            // Anchored to TopEnd so it stays fixed relative to content start
            if (alphabetIndex != null) {
                AlphabetScrollbar(
                    alphabetIndex = alphabetIndex,
                    onLetterSelected = { index ->
                        scope.launch { gridState.scrollToItem(index) }
                    },
                    isScrolling = isScrolling,
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 56.dp, end = 4.dp, bottom = 0.dp),
                )
            }
        }
    }
}

/**
 * Empty state when no series in library.
 */
@Composable
private fun SeriesEmptyState() {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainerLow),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.AutoStories,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
            Text(
                text = stringResource(Res.string.common_no_items_yet, "series"),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(Res.string.library_empty_tab_description, "Series"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
