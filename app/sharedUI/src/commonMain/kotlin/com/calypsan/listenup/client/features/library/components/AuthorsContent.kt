package com.calypsan.listenup.client.features.library.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.AlphabetIndex
import com.calypsan.listenup.client.design.components.ContributorCoverImage
import com.calypsan.listenup.client.design.components.AlphabetScrollbar
import com.calypsan.listenup.client.design.components.SortSplitButton
import com.calypsan.listenup.client.design.components.cookieScallopShape
import androidx.compose.ui.graphics.Color
import com.calypsan.listenup.client.domain.model.ContributorWithBookCount
import com.calypsan.listenup.client.presentation.library.SortCategory
import com.calypsan.listenup.client.presentation.library.SortState
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.library_empty_tab_description
import listenup.composeapp.generated.resources.common_no_items_yet
import listenup.composeapp.generated.resources.contributor_name_profile_image
import listenup.composeapp.generated.resources.genre_book_count
import listenup.composeapp.generated.resources.genre_books_count

/**
 * Content for the Authors tab in the Library screen.
 *
 * Displays a list of authors with their book counts.
 *
 * @param authors List of authors with book counts
 * @param sortState Current sort state (category + direction)
 * @param onCategorySelected Called when user selects a new category
 * @param onDirectionToggle Called when user toggles sort direction
 * @param onAuthorClick Callback when an author is clicked
 * @param modifier Optional modifier
 */
@Composable
fun AuthorsContent(
    authors: List<ContributorWithBookCount>,
    sortState: SortState,
    onCategorySelected: (SortCategory) -> Unit,
    onDirectionToggle: () -> Unit,
    onAuthorClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        if (authors.isEmpty()) {
            AuthorsEmptyState()
        } else {
            val listState = rememberLazyListState()
            val scope = rememberCoroutineScope()

            // Build alphabet index for name-based sort
            val alphabetIndex =
                remember(authors, sortState) {
                    if (sortState.category == SortCategory.NAME) {
                        AlphabetIndex.build(authors) { it.contributor.name }
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
                    items = authors,
                    key = { it.contributor.id.value },
                ) { authorWithCount ->
                    ContributorCard(
                        contributorWithCount = authorWithCount,
                        onClick = { onAuthorClick(authorWithCount.contributor.id.value) },
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
 * Card displaying a contributor (author/narrator) with their book count.
 * Shows contributor image if available, otherwise displays initials.
 */
@Composable
internal fun ContributorCard(
    contributorWithCount: ContributorWithBookCount,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contributor = contributorWithCount.contributor

    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Avatar with image or initials
            Surface(
                shape = cookieScallopShape(),
                color = Color.hsl((contributor.id.value.hashCode() and 0x7FFFFFFF).rem(360).toFloat(), 0.4f, 0.65f),
                modifier = Modifier.size(48.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    var imageLoaded by remember(contributor.id.value) { mutableStateOf(false) }

                    if (!imageLoaded) {
                        Text(
                            text =
                                contributor.name
                                    .trim()
                                    .split("\\s+".toRegex())
                                    .let { parts ->
                                        when {
                                            parts.size >= 2 -> "${parts[0].first()}${parts[1].first()}"
                                            contributor.name.length >= 2 -> contributor.name.take(2)
                                            else -> contributor.name.take(1)
                                        }
                                    }.uppercase(),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }

                    ContributorCoverImage(
                        contributorId = contributor.id.value,
                        imagePath = contributor.imagePath,
                        contentDescription =
                            stringResource(
                                Res.string.contributor_name_profile_image,
                                contributor.name,
                            ),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                        onState = { state ->
                            if (state is coil3.compose.AsyncImagePainter.State.Success) {
                                imageLoaded = true
                            }
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Contributor info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = contributor.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text =
                        if (contributorWithCount.bookCount == 1) {
                            stringResource(Res.string.genre_book_count, contributorWithCount.bookCount)
                        } else {
                            stringResource(Res.string.genre_books_count, contributorWithCount.bookCount)
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Empty state when no authors in library.
 */
@Composable
private fun AuthorsEmptyState() {
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
                imageVector = Icons.Outlined.Person,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
            Text(
                text = stringResource(Res.string.common_no_items_yet, "authors"),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(Res.string.library_empty_tab_description, "Authors"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
