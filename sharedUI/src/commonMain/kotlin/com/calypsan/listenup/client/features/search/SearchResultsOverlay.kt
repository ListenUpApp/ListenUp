package com.calypsan.listenup.client.features.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import com.calypsan.listenup.client.design.components.BookCoverImage
import com.calypsan.listenup.client.design.components.FullScreenLoadingIndicator
import com.calypsan.listenup.client.design.components.cookieScallopShape
import com.calypsan.listenup.client.domain.model.SearchHit
import com.calypsan.listenup.client.domain.model.SearchHitType
import com.calypsan.listenup.client.domain.model.SearchResult
import com.calypsan.listenup.client.features.library.BookCard
import com.calypsan.listenup.client.presentation.search.SearchUiState
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_tags
import listenup.composeapp.generated.resources.common_series
import listenup.composeapp.generated.resources.genre_book_count
import listenup.composeapp.generated.resources.genre_books_count
import listenup.composeapp.generated.resources.library_books
import listenup.composeapp.generated.resources.search_count_books
import listenup.composeapp.generated.resources.search_cover_for
import listenup.composeapp.generated.resources.search_no_results_for_query
import listenup.composeapp.generated.resources.search_people
import listenup.composeapp.generated.resources.search_section_count
import listenup.composeapp.generated.resources.search_try_a_different_search_term
import listenup.composeapp.generated.resources.shell_close_search

/**
 * Full-screen overlay for search results.
 *
 * Displays federated results grouped by type (Books, Authors, Series, Tags).
 * [isExpanded] is owned by the enclosing screen (search bar) and combined here
 * with `state.query` to drive entry/exit animation.
 *
 * Layout is width-adaptive: at compact width the groups stack in a single column;
 * at medium-or-wider width Books move into a multi-column cover grid beside a
 * People / Series / Tags rail — mirroring the iPad search design.
 */
@Composable
fun SearchResultsOverlay(
    state: SearchUiState,
    isExpanded: Boolean,
    onClose: () -> Unit,
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
                // The overlay covers the shell header (and its back arrow), so it carries its own
                // back affordance — the visible counterpart to the system-back handler in AppShell.
                SearchOverlayTopBar(query = state.query, onClose = onClose)

                TypeFilterRow(
                    selectedTypes = state.selectedTypes,
                    onToggle = onTypeFilterToggle,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )

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

    // Width signal: at medium+ width Books get a cover grid beside a People/Series/Tags rail.
    val isWide =
        currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(
            WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND,
        )

    if (isWide) {
        WideSearchResults(
            books = books,
            contributors = contributors,
            series = series,
            tags = tags,
            onResultClick = onResultClick,
            modifier = modifier,
        )
    } else {
        SearchResultsList(
            books = books,
            contributors = contributors,
            series = series,
            tags = tags,
            onResultClick = onResultClick,
            modifier = modifier,
        )
    }
}

@Composable
private fun SearchOverlayTopBar(
    query: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 16.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(Res.string.shell_close_search),
            )
        }
        Text(
            text = query,
            style = MaterialTheme.typography.titleLargeEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@Composable
private fun TypeFilterRow(
    selectedTypes: Set<SearchHitType>,
    onToggle: (SearchHitType) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SearchScopeChip(
            type = SearchHitType.BOOK,
            selectedTypes = selectedTypes,
            onToggle = onToggle,
            label = stringResource(Res.string.library_books),
            icon = Icons.Default.Book,
        )
        SearchScopeChip(
            type = SearchHitType.CONTRIBUTOR,
            selectedTypes = selectedTypes,
            onToggle = onToggle,
            label = stringResource(Res.string.search_people),
            icon = Icons.Default.Person,
        )
        SearchScopeChip(
            type = SearchHitType.SERIES,
            selectedTypes = selectedTypes,
            onToggle = onToggle,
            label = stringResource(Res.string.common_series),
            icon = Icons.AutoMirrored.Filled.PlaylistPlay,
        )
        SearchScopeChip(
            type = SearchHitType.TAG,
            selectedTypes = selectedTypes,
            onToggle = onToggle,
            label = stringResource(Res.string.book_detail_tags),
            icon = Icons.Default.Tag,
        )
    }
}

/**
 * A single multi-select scope chip. Empty selection means "all", so each chip reads as
 * selected when its type is chosen or when nothing is filtered.
 */
@Composable
private fun SearchScopeChip(
    type: SearchHitType,
    selectedTypes: Set<SearchHitType>,
    onToggle: (SearchHitType) -> Unit,
    label: String,
    icon: ImageVector,
) {
    FilterChip(
        selected = type in selectedTypes || selectedTypes.isEmpty(),
        onClick = { onToggle(type) },
        label = { Text(label) },
        shape = MaterialTheme.shapes.large,
        leadingIcon = {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        },
    )
}

/**
 * Compact (phone) layout: every group stacked in one scrolling column.
 */
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

/**
 * Medium/expanded layout: Books fill a responsive cover grid on the left, with People,
 * Series and Tags stacked in a narrower rail on the right (mirrors the iPad design).
 * Each pane scrolls independently so a long Books list doesn't bury the people rail.
 */
@Composable
private fun WideSearchResults(
    books: List<SearchHit>,
    contributors: List<SearchHit>,
    series: List<SearchHit>,
    tags: List<SearchHit>,
    onResultClick: (SearchHit) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        if (books.isNotEmpty()) {
            BooksGrid(
                books = books,
                onResultClick = onResultClick,
                modifier = Modifier.weight(1.4f),
            )
        }

        // People / Series / Tags rail. Capped width keeps rows comfortable on very wide windows.
        if (contributors.isNotEmpty() || series.isNotEmpty() || tags.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier.weight(1f).widthIn(max = 420.dp),
                contentPadding = PaddingValues(vertical = 16.dp),
            ) {
                if (contributors.isNotEmpty()) {
                    item(key = "contributors_header") {
                        SectionHeader(title = stringResource(Res.string.search_people), count = contributors.size)
                    }
                    items(contributors, key = { "contributor_${it.id}" }) { hit ->
                        ContributorSearchResultCard(hit = hit, onClick = { onResultClick(hit) })
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
                if (series.isNotEmpty()) {
                    item(key = "series_header") {
                        SectionHeader(title = stringResource(Res.string.common_series), count = series.size)
                    }
                    items(series, key = { "series_${it.id}" }) { hit ->
                        SeriesSearchResultCard(hit = hit, onClick = { onResultClick(hit) })
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
                if (tags.isNotEmpty()) {
                    item(key = "tags_header") {
                        SectionHeader(title = stringResource(Res.string.book_detail_tags), count = tags.size)
                    }
                    items(tags, key = { "tag_${it.id}" }) { hit ->
                        TagSearchResultCard(hit = hit, onClick = { onResultClick(hit) })
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

/**
 * Responsive cover grid of book hits, reusing the canonical library [BookCard].
 * `GridCells.Adaptive` keeps the column count fluid with available width.
 */
@Composable
private fun BooksGrid(
    books: List<SearchHit>,
    onResultClick: (SearchHit) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 160.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "books_header", span = { GridItemSpan(maxLineSpan) }) {
            SectionHeader(title = stringResource(Res.string.library_books), count = books.size)
        }
        gridItems(books, key = { "book_${it.id}" }) { hit ->
            BookCard(
                bookId = hit.id,
                title = hit.name,
                coverPath = hit.coverPath,
                blurHash = null,
                onClick = { onResultClick(hit) },
                authorName = hit.author,
                subtitle = hit.seriesName,
                duration = hit.formatDuration(),
            )
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
            style = MaterialTheme.typography.titleLargeEmphasized,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(Res.string.search_section_count, count),
            style = MaterialTheme.typography.bodyMedium,
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
        shape = MaterialTheme.shapes.large,
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
                contentDescription = stringResource(Res.string.search_cover_for, hit.name),
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier
                        .size(56.dp)
                        .clip(MaterialTheme.shapes.small),
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
        shape = MaterialTheme.shapes.large,
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SearchAvatarTile(
                icon = Icons.Default.Person,
                container = MaterialTheme.colorScheme.primaryContainer,
                onContainer = MaterialTheme.colorScheme.onPrimaryContainer,
            )

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
        shape = MaterialTheme.shapes.large,
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SearchAvatarTile(
                icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                container = MaterialTheme.colorScheme.tertiaryContainer,
                onContainer = MaterialTheme.colorScheme.onTertiaryContainer,
            )

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
        shape = MaterialTheme.shapes.large,
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SearchAvatarTile(
                icon = Icons.Default.Tag,
                container = MaterialTheme.colorScheme.secondaryContainer,
                onContainer = MaterialTheme.colorScheme.onSecondaryContainer,
            )

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
                        text =
                            if (count == 1) {
                                stringResource(Res.string.genre_book_count, count)
                            } else {
                                stringResource(Res.string.genre_books_count, count)
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * The Expressive scalloped "cookie" tile used as the leading glyph for non-book hits
 * (People / Series / Tags), tinted to its container role.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SearchAvatarTile(
    icon: ImageVector,
    container: Color,
    onContainer: Color,
) {
    Box(
        modifier =
            Modifier
                .size(48.dp)
                .clip(cookieScallopShape())
                .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = onContainer,
        )
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
