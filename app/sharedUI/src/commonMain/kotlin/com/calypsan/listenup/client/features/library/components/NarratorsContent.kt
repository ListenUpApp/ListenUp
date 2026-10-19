package com.calypsan.listenup.client.features.library.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.AlphabetScrollbar
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.domain.model.ContributorWithBookCount
import com.calypsan.listenup.client.presentation.library.LetterSection
import com.calypsan.listenup.client.presentation.library.SortCategory
import com.calypsan.listenup.client.presentation.library.SortState
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.library_empty_tab_description
import listenup.composeapp.generated.resources.common_no_items_yet

/**
 * Content for the Narrators tab in the Library screen.
 *
 * Displays a list of narrators with their book counts.
 *
 * @param narrators List of narrators with book counts
 * @param sortState Current sort state (category + direction)
 * @param letters The shared letter sections over the list; empty for a non-name sort
 * @param onCategorySelected Called when user selects a new category
 * @param onDirectionToggle Called when user toggles sort direction
 * @param onNarratorClick Callback when a narrator is clicked
 * @param modifier Optional modifier
 */
@Composable
fun NarratorsContent(
    narrators: List<ContributorWithBookCount>,
    sortState: SortState,
    letters: List<LetterSection>,
    onCategorySelected: (SortCategory) -> Unit,
    onDirectionToggle: () -> Unit,
    onNarratorClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        if (narrators.isEmpty()) {
            NarratorsEmptyState()
        } else {
            val listState = rememberLazyListState()
            val scope = rememberCoroutineScope()

            // The shared letter sections: empty on a non-name sort, so no scrollbar.
            val alphabetIndex =
                remember(letters) { libraryAlphabetIndex(letters, withHeaders = true, leadingItems = 0) }
            val rows = remember(narrators, letters) { letteredRows(narrators, letters) { it.contributor.id.value } }

            val isScrolling by remember {
                derivedStateOf { listState.isScrollInProgress }
            }

            Column(modifier = Modifier.fillMaxSize()) {
                // The same sort control as Books and Series, at the top of the list (spec §5.1 c5).
                LibrarySortCard(
                    state = sortState,
                    categories = SortCategory.contributorCategories,
                    count = narrators.size,
                    unit = if (narrators.size == 1) "narrator" else "narrators",
                    ignoreArticles = false,
                    showArticleToggle = false,
                    onCategorySelected = onCategorySelected,
                    onDirectionToggle = onDirectionToggle,
                    onToggleArticles = {},
                    visible = true,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                )

                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        contentPadding =
                            PaddingValues(
                                start = Spacing.gridMargin,
                                end = Spacing.gridMargin,
                                top = 12.dp,
                                bottom = 16.dp,
                            ),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        contributorRows(rows, onNarratorClick)
                    }

                    // Alphabet scrollbar (only for name sort)
                    // Anchored to TopEnd so it stays fixed relative to content start
                    if (alphabetIndex != null) {
                        AlphabetScrollbar(
                            alphabetIndex = alphabetIndex,
                            onLetterSelected = { index ->
                                scope.launch { listState.scrollToItem(index) }
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
 * Empty state when no narrators in library.
 */
@Composable
private fun NarratorsEmptyState() {
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
                imageVector = Icons.Outlined.RecordVoiceOver,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.outline,
            )
            Text(
                text = stringResource(Res.string.common_no_items_yet, "narrators"),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(Res.string.library_empty_tab_description, "Narrators"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
