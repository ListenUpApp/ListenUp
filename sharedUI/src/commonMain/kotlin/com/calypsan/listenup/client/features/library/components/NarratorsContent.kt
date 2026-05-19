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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RecordVoiceOver
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
import com.calypsan.listenup.client.domain.model.ContributorWithBookCount
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
 * @param onCategorySelected Called when user selects a new category
 * @param onDirectionToggle Called when user toggles sort direction
 * @param onNarratorClick Callback when a narrator is clicked
 * @param modifier Optional modifier
 */
@Composable
fun NarratorsContent(
    narrators: List<ContributorWithBookCount>,
    sortState: SortState,
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

            // Build alphabet index for name-based sort
            val alphabetIndex =
                remember(narrators, sortState) {
                    if (sortState.category == SortCategory.NAME) {
                        AlphabetIndex.build(narrators) { it.contributor.name }
                    } else {
                        null
                    }
                }

            val isScrolling by remember {
                derivedStateOf { listState.isScrollInProgress }
            }

            // Track scroll direction for button visibility
            var previousScrollOffset by remember { mutableIntStateOf(0) }
            val showSortButton by remember {
                derivedStateOf {
                    val firstVisible = listState.firstVisibleItemIndex
                    val currentOffset = listState.firstVisibleItemScrollOffset

                    val isAtTop = firstVisible == 0 && currentOffset < 50
                    val isScrollingUp = currentOffset < previousScrollOffset

                    previousScrollOffset = currentOffset
                    isAtTop || isScrollingUp || !listState.isScrollInProgress
                }
            }

            LazyColumn(
                state = listState,
                contentPadding =
                    PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 48.dp,
                        bottom = 16.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(
                    items = narrators,
                    key = { it.contributor.id.value },
                ) { narratorWithCount ->
                    // Reuse ContributorCard from AuthorsContent
                    ContributorCard(
                        contributorWithCount = narratorWithCount,
                        onClick = { onNarratorClick(narratorWithCount.contributor.id.value) },
                    )
                }
            }

            // Sort split button
            SortSplitButton(
                state = sortState,
                categories = SortCategory.contributorCategories,
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
                        scope.launch { listState.scrollToItem(index) }
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
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
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
