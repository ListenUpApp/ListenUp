package com.calypsan.listenup.client.features.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.BookCoverImage
import com.calypsan.listenup.client.design.components.FullScreenLoadingIndicator
import com.calypsan.listenup.client.domain.model.SearchHit
import com.calypsan.listenup.client.domain.model.SearchHitType
import com.calypsan.listenup.client.domain.model.SearchResult
import com.calypsan.listenup.client.presentation.search.SearchUiState
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_tags
import listenup.composeapp.generated.resources.common_series
import listenup.composeapp.generated.resources.book_edit_showing_offline_results
import listenup.composeapp.generated.resources.library_books
import listenup.composeapp.generated.resources.search_count_books
import listenup.composeapp.generated.resources.search_no_results_for_query
import listenup.composeapp.generated.resources.search_people
import listenup.composeapp.generated.resources.search_try_a_different_search_term

/**
 * Full-screen overlay for search results.
 *
 * Displays federated results grouped by type (Books, Authors, Series, Tags).
 * [isExpanded] is owned by the enclosing screen (search bar) and combined here
 * with `state.query` to drive entry/exit animation.
 */
@Composable
fun SearchResultsOverlay(
    state: SearchUiState,
    isExpanded: Boolean,
    onResultClick: (SearchHit) -> Unit,
    onTypeFilterToggle: (SearchHitType) -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = isExpanded && state.query.isNotBlank(),
        enter = fadeIn() + slideInVertically(),
        exit = fadeOut() + slideOutVertically(),
        modifier = modifier,
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                TypeFilterRow(
                    selectedTypes = state.selectedTypes,
                    onToggle = onTypeFilterToggle,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )

                if (state is SearchUiState.Results && state.result.isOfflineResult) {
                    OfflineIndicator(modifier = Modifier.padding(horizontal = 16.dp))
                }

                when (state) {
                    is SearchUiState.Idle -> {
                    }

                    is SearchUiState.Searching -> {
                        LoadingState(modifier = Modifier.weight(1f))
                    }

                    is SearchUiState.Error -> {
                        ErrorState(
                            message = state.message,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    is SearchUiState.Results -> {
                        ResultsContent(
                            result = state.result,
                            query = state.query,
                            onResultClick = onResultClick,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultsContent(
    result: SearchResult,
    query: String,
    onResultClick: (SearchHit) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (result.hits.isEmpty()) {
        EmptyState(query = query, modifier = modifier)
        return
    }
    val grouped = result.hits.groupBy { it.type }
    val books = grouped[SearchHitType.BOOK].orEmpty().distinctBy { it.id }
    val contributors = grouped[SearchHitType.CONTRIBUTOR].orEmpty().distinctBy { it.id }
    val series = grouped[SearchHitType.SERIES].orEmpty().distinctBy { it.id }
    val tags = grouped[SearchHitType.TAG].orEmpty().distinctBy { it.id }
    SearchResultsList(
        books = books,
        contributors = contributors,
        series = series,
        tags = tags,
        onResultClick = onResultClick,
        modifier = modifier,
    )
}

@Composable
private fun TypeFilterRow(
    selectedTypes: Set<SearchHitType>,
    onToggle: (SearchHitType) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier) {
        FilterChip(
            selected = SearchHitType.BOOK in selectedTypes || selectedTypes.isEmpty(),
            onClick = { onToggle(SearchHitType.BOOK) },
            label = { Text(stringResource(Res.string.library_books)) },
            leadingIcon = {
                Icon(
                    Icons.Default.Book,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
        )
        Spacer(modifier = Modifier.width(8.dp))
        FilterChip(
            selected = SearchHitType.CONTRIBUTOR in selectedTypes || selectedTypes.isEmpty(),
            onClick = { onToggle(SearchHitType.CONTRIBUTOR) },
            label = { Text(stringResource(Res.string.search_people)) },
            leadingIcon = {
                Icon(
                    Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
        )
        Spacer(modifier = Modifier.width(8.dp))
        FilterChip(
            selected = SearchHitType.SERIES in selectedTypes || selectedTypes.isEmpty(),
            onClick = { onToggle(SearchHitType.SERIES) },
            label = { Text(stringResource(Res.string.common_series)) },
            leadingIcon = {
                Icon(
                    Icons.AutoMirrored.Filled.PlaylistPlay,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
        )
        Spacer(modifier = Modifier.width(8.dp))
        FilterChip(
            selected = SearchHitType.TAG in selectedTypes || selectedTypes.isEmpty(),
            onClick = { onToggle(SearchHitType.TAG) },
            label = { Text(stringResource(Res.string.book_detail_tags)) },
            leadingIcon = {
                Icon(
                    Icons.Default.Tag,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
        )
    }
}

@Composable
private fun OfflineIndicator(modifier: Modifier = Modifier) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.secondaryContainer,
                    RoundedCornerShape(8.dp),
                ).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.CloudOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(Res.string.book_edit_showing_offline_results),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun SearchResultsList(
    books: List<SearchHit>,
    contributors: List<SearchHit>,
    series: List<SearchHit>,
    tags: List<SearchHit>,
    onResultClick: (SearchHit) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
    ) {
        if (books.isNotEmpty()) {
            item(key = "books_header") {
                SectionHeader(title = stringResource(Res.string.library_books), count = books.size)
            }
            items(books, key = { "book_${it.id}" }) { hit ->
                BookSearchResultCard(
                    hit = hit,
                    onClick = { onResultClick(hit) },
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        if (contributors.isNotEmpty()) {
            item(key = "contributors_header") {
                SectionHeader(title = stringResource(Res.string.search_people), count = contributors.size)
            }
            items(contributors, key = { "contributor_${it.id}" }) { hit ->
                ContributorSearchResultCard(
                    hit = hit,
                    onClick = { onResultClick(hit) },
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        if (series.isNotEmpty()) {
            item(key = "series_header") {
                SectionHeader(title = stringResource(Res.string.common_series), count = series.size)
            }
            items(series, key = { "series_${it.id}" }) { hit ->
                SeriesSearchResultCard(
                    hit = hit,
                    onClick = { onResultClick(hit) },
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        if (tags.isNotEmpty()) {
            item(key = "tags_header") {
                SectionHeader(title = stringResource(Res.string.book_detail_tags), count = tags.size)
            }
            items(tags, key = { "tag_${it.id}" }) { hit ->
                TagSearchResultCard(
                    hit = hit,
                    onClick = { onResultClick(hit) },
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    count: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "($count)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BookSearchResultCard(
    hit: SearchHit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BookCoverImage(
                bookId = hit.id,
                coverPath = hit.coverPath,
                contentDescription = "Cover for ${hit.name}",
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(4.dp)),
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = hit.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                hit.author?.let { author ->
                    Text(
                        text = author,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                hit.seriesName?.let { seriesName ->
                    Text(
                        text = seriesName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            hit.formatDuration()?.let { duration ->
                Text(
                    text = duration,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ContributorSearchResultCard(
    hit: SearchHit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(48.dp)
                        .background(
                            MaterialTheme.colorScheme.primaryContainer,
                            RoundedCornerShape(24.dp),
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = hit.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                hit.bookCount?.let { count ->
                    Text(
                        text = stringResource(Res.string.search_count_books, count),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SeriesSearchResultCard(
    hit: SearchHit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(48.dp)
                        .background(
                            MaterialTheme.colorScheme.tertiaryContainer,
                            RoundedCornerShape(8.dp),
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.PlaylistPlay,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = hit.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                hit.bookCount?.let { count ->
                    Text(
                        text = stringResource(Res.string.search_count_books, count),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun TagSearchResultCard(
    hit: SearchHit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(48.dp)
                        .background(
                            MaterialTheme.colorScheme.secondaryContainer,
                            RoundedCornerShape(24.dp),
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Tag,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = hit.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                hit.bookCount?.let { count ->
                    Text(
                        text = "$count ${if (count == 1) "book" else "books"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingState(modifier: Modifier = Modifier) {
    FullScreenLoadingIndicator(modifier = modifier)
}

@Composable
private fun EmptyState(
    query: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(Res.string.search_no_results_for_query, query),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(Res.string.search_try_a_different_search_term),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ErrorState(
    message: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
