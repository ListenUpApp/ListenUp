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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.AlphabetScrollbar
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.domain.model.SeriesProgress
import com.calypsan.listenup.client.domain.model.SeriesWithBooks
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.client.presentation.library.LetterSection
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
 * @param seriesProgress Per-series finished counts; a series missing from it reads as not started
 * @param sortState Current sort state (category + direction)
 * @param letters The shared letter sections over [series]; empty for a non-name sort
 * @param ignoreArticles Whether leading articles (A / An / The) are ignored when sorting by name
 * @param onCategorySelected Called when user selects a new category
 * @param onDirectionToggle Called when user toggles sort direction
 * @param onToggleIgnoreArticles Called when the "Title sort" toggle is tapped (article-aware name sort)
 * @param onSeriesClick Callback when a series is clicked (passes series ID)
 * @param modifier Optional modifier
 */
@Composable
fun SeriesContent(
    series: List<SeriesWithBooks>,
    seriesProgress: Map<SeriesId, SeriesProgress>,
    sortState: SortState,
    letters: List<LetterSection>,
    ignoreArticles: Boolean,
    onCategorySelected: (SortCategory) -> Unit,
    onDirectionToggle: () -> Unit,
    onToggleIgnoreArticles: () -> Unit,
    onSeriesClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        if (series.isEmpty()) {
            SeriesEmptyState()
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                // Mirrors the Books tab: one unified sort card holds category, direction, and the
                // article toggle (article-aware name sorting) together.
                LibrarySortCard(
                    state = sortState,
                    categories = SortCategory.seriesCategories,
                    count = series.size,
                    unit = "series",
                    ignoreArticles = ignoreArticles,
                    showArticleToggle = sortState.category == SortCategory.NAME,
                    onCategorySelected = onCategorySelected,
                    onDirectionToggle = onDirectionToggle,
                    onToggleArticles = onToggleIgnoreArticles,
                    visible = true,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                )

                Box(modifier = Modifier.fillMaxSize()) {
                    val gridState = rememberLazyGridState()
                    val scope = rememberCoroutineScope()

                    // The shared letter sections: name sort only, article-aware so "The Wandering Inn"
                    // files under W.
                    val alphabetIndex =
                        remember(letters) { libraryAlphabetIndex(letters, withHeaders = true, leadingItems = 0) }
                    val rows = remember(series, letters) { letteredRows(series, letters) { it.series.id.value } }

                    val isScrolling by remember {
                        derivedStateOf { gridState.isScrollInProgress }
                    }

                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Adaptive(minSize = 200.dp),
                        contentPadding =
                            PaddingValues(
                                start = Spacing.gridMargin,
                                end = Spacing.gridMargin,
                                top = 12.dp,
                                bottom = 16.dp,
                            ),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        seriesRows(rows, seriesProgress, onSeriesClick)
                    }

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
                                    .padding(top = 12.dp, end = 4.dp, bottom = 0.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The Series grid's rows: a full-width header before each letter on a name sort (spec L25), from the
 * shared `seriesLetters`, and a card per series.
 */
private fun LazyGridScope.seriesRows(
    rows: List<LetteredRow<SeriesWithBooks>>,
    seriesProgress: Map<SeriesId, SeriesProgress>,
    onSeriesClick: (String) -> Unit,
) {
    items(
        items = rows,
        key = { it.key },
        span = { if (it is LetteredRow.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
        contentType = { if (it is LetteredRow.Header) "header" else "series" },
    ) { row ->
        when (row) {
            is LetteredRow.Header -> {
                SectionHeader(letter = row.letter)
            }

            is LetteredRow.Entry -> {
                val seriesWithBooks = row.value
                SeriesCard(
                    seriesWithBooks = seriesWithBooks,
                    // iOS's `progressFor` fallback: no aggregate for a series means nothing finished yet.
                    progress =
                        seriesProgress[seriesWithBooks.series.id]
                            ?: SeriesProgress(finishedCount = 0, totalCount = seriesWithBooks.books.size),
                    onClick = { onSeriesClick(seriesWithBooks.series.id.value) },
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
                tint = MaterialTheme.colorScheme.outline,
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
