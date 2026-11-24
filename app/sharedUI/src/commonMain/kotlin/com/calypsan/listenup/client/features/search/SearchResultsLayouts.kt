package com.calypsan.listenup.client.features.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.toCoverModel
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.domain.model.SearchHit
import com.calypsan.listenup.client.domain.model.SearchHitType
import com.calypsan.listenup.client.features.library.BookCard
import com.calypsan.listenup.client.presentation.search.SearchResultCaps
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_tags
import listenup.composeapp.generated.resources.common_series
import listenup.composeapp.generated.resources.library_books
import listenup.composeapp.generated.resources.search_people
import org.jetbrains.compose.resources.stringResource

// Stable lazy-list item keys, shared across the compact list, the wide rail, and the See-all page
// so a single hit keeps the same identity wherever it renders.
private const val BOOKS_HEADER_KEY = "books_header"
private const val CONTRIBUTORS_HEADER_KEY = "contributors_header"
private const val SERIES_HEADER_KEY = "series_header"
private const val TAGS_HEADER_KEY = "tags_header"
internal const val TAGS_FLOW_KEY = "tags_flow"
internal const val BOOK_KEY_PREFIX = "book_"
internal const val CONTRIBUTOR_KEY_PREFIX = "contributor_"
internal const val SERIES_KEY_PREFIX = "series_"

internal const val CONTENT_TYPE_GROUP_HEADER = "group-header"
internal const val CONTENT_TYPE_BOOK_ROW = "book-row"
private const val CONTENT_TYPE_BOOK_CARD = "book-card"
internal const val CONTENT_TYPE_PERSON_ROW = "person-row"
internal const val CONTENT_TYPE_SERIES_ROW = "series-row"
internal const val CONTENT_TYPE_TAG_FLOW = "tag-flow"

/** Stable lazy-list item key for a hit, namespaced by [prefix] so types never collide. */
internal fun hitKey(
    prefix: String,
    hit: SearchHit,
): String = "$prefix${hit.id}"

/**
 * Compact (phone) layout: every group stacked in one scrolling column.
 */
@Composable
internal fun SearchResultsList(
    books: List<SearchHit>,
    contributors: List<SearchHit>,
    series: List<SearchHit>,
    tags: List<SearchHit>,
    query: String,
    onResultClick: (SearchHit) -> Unit,
    onSeeAll: (SearchHitType) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding =
            PaddingValues(
                start = Spacing.screenMargin,
                end = Spacing.screenMargin,
                top = Spacing.sm,
                bottom = Spacing.xl,
            ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (books.isNotEmpty()) {
            item(key = BOOKS_HEADER_KEY, contentType = CONTENT_TYPE_GROUP_HEADER) {
                GroupHeader(
                    title = stringResource(Res.string.library_books),
                    count = books.size,
                    badgeContainer = MaterialTheme.colorScheme.primary,
                    badgeContent = MaterialTheme.colorScheme.onPrimary,
                    onSeeAll = seeAllCallback(books.size, SearchResultCaps.BOOK) { onSeeAll(SearchHitType.BOOK) },
                )
            }
            items(
                items = books.take(SearchResultCaps.BOOK),
                key = { hitKey(BOOK_KEY_PREFIX, it) },
                contentType = { CONTENT_TYPE_BOOK_ROW },
            ) { hit ->
                BookResultRow(hit = hit, query = query, onClick = { onResultClick(hit) })
            }
        }

        contributorGroup(
            contributors = contributors,
            query = query,
            onResultClick = onResultClick,
            onSeeAll = onSeeAll,
        )
        seriesGroup(
            series = series,
            query = query,
            onResultClick = onResultClick,
            onSeeAll = onSeeAll,
        )
        tagGroup(tags, query, onResultClick)
    }
}

/**
 * Medium/expanded layout: Books fill a responsive cover grid on the left, with People,
 * Series and Tags stacked in a narrower rail on the right (mirrors the iPad design).
 * Each pane scrolls independently so a long Books list doesn't bury the people rail.
 */
@Composable
internal fun WideSearchResults(
    books: List<SearchHit>,
    contributors: List<SearchHit>,
    series: List<SearchHit>,
    tags: List<SearchHit>,
    query: String,
    onResultClick: (SearchHit) -> Unit,
    onSeeAll: (SearchHitType) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxSize().padding(horizontal = Spacing.screenMargin),
        horizontalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        if (books.isNotEmpty()) {
            BooksGrid(
                books = books.take(SearchResultCaps.BOOK),
                totalBookCount = books.size,
                onResultClick = onResultClick,
                onSeeAll = { onSeeAll(SearchHitType.BOOK) },
                modifier = Modifier.weight(1.4f),
            )
        }

        // People / Series / Tags rail. Capped width keeps rows comfortable on very wide windows.
        if (contributors.isNotEmpty() || series.isNotEmpty() || tags.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier.weight(1f).widthIn(max = 420.dp),
                contentPadding = PaddingValues(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                contributorGroup(
                    contributors = contributors,
                    query = query,
                    onResultClick = onResultClick,
                    onSeeAll = onSeeAll,
                )
                seriesGroup(
                    series = series,
                    query = query,
                    onResultClick = onResultClick,
                    onSeeAll = onSeeAll,
                )
                tagGroup(tags, query, onResultClick)
            }
        }
    }
}

/** People group: capped rows with a "See all" header action when more than the cap exist. */
private fun LazyListScope.contributorGroup(
    contributors: List<SearchHit>,
    query: String,
    onResultClick: (SearchHit) -> Unit,
    onSeeAll: (SearchHitType) -> Unit,
) {
    if (contributors.isEmpty()) return
    item(key = CONTRIBUTORS_HEADER_KEY, contentType = CONTENT_TYPE_GROUP_HEADER) {
        GroupHeader(
            title = stringResource(Res.string.search_people),
            count = contributors.size,
            badgeContainer = MaterialTheme.colorScheme.tertiaryContainer,
            badgeContent = MaterialTheme.colorScheme.onTertiaryContainer,
            onSeeAll =
                seeAllCallback(contributors.size, SearchResultCaps.CONTRIBUTOR) {
                    onSeeAll(SearchHitType.CONTRIBUTOR)
                },
        )
    }
    items(
        items = contributors.take(SearchResultCaps.CONTRIBUTOR),
        key = { hitKey(CONTRIBUTOR_KEY_PREFIX, it) },
        contentType = { CONTENT_TYPE_PERSON_ROW },
    ) { hit ->
        PersonResultRow(hit = hit, query = query, onClick = { onResultClick(hit) })
    }
}

/** Series group: capped rows with a "See all" header action when more than the cap exist. */
private fun LazyListScope.seriesGroup(
    series: List<SearchHit>,
    query: String,
    onResultClick: (SearchHit) -> Unit,
    onSeeAll: (SearchHitType) -> Unit,
) {
    if (series.isEmpty()) return
    item(key = SERIES_HEADER_KEY, contentType = CONTENT_TYPE_GROUP_HEADER) {
        GroupHeader(
            title = stringResource(Res.string.common_series),
            count = series.size,
            badgeContainer = MaterialTheme.colorScheme.secondaryContainer,
            badgeContent = MaterialTheme.colorScheme.onSecondaryContainer,
            onSeeAll = seeAllCallback(series.size, SearchResultCaps.SERIES) { onSeeAll(SearchHitType.SERIES) },
        )
    }
    items(
        items = series.take(SearchResultCaps.SERIES),
        key = { hitKey(SERIES_KEY_PREFIX, it) },
        contentType = { CONTENT_TYPE_SERIES_ROW },
    ) { hit ->
        SeriesResultRow(hit = hit, query = query, onClick = { onResultClick(hit) })
    }
}

/** Tags group: a single wrapping pill flow. Tags are never capped — they render inline. */
private fun LazyListScope.tagGroup(
    tags: List<SearchHit>,
    query: String,
    onResultClick: (SearchHit) -> Unit,
) {
    if (tags.isEmpty()) return
    item(key = TAGS_HEADER_KEY, contentType = CONTENT_TYPE_GROUP_HEADER) {
        GroupHeader(
            title = stringResource(Res.string.book_detail_tags),
            count = tags.size,
            badgeContainer = MaterialTheme.colorScheme.primaryContainer,
            badgeContent = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
    item(key = TAGS_FLOW_KEY, contentType = CONTENT_TYPE_TAG_FLOW) {
        TagFlow(tags = tags, query = query, onResultClick = onResultClick)
    }
}

/** Returns a header "See all" callback when [total] exceeds [cap], else null (no affordance). */
private fun seeAllCallback(
    total: Int,
    cap: Int,
    onSeeAll: () -> Unit,
): (() -> Unit)? = if (total > cap) onSeeAll else null

/**
 * Responsive cover grid of book hits, reusing the canonical library [BookCard].
 * `GridCells.Adaptive` keeps the column count fluid with available width.
 */
@Composable
private fun BooksGrid(
    books: List<SearchHit>,
    totalBookCount: Int,
    onResultClick: (SearchHit) -> Unit,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 160.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(
            key = BOOKS_HEADER_KEY,
            span = { GridItemSpan(maxLineSpan) },
            contentType = CONTENT_TYPE_GROUP_HEADER,
        ) {
            GroupHeader(
                title = stringResource(Res.string.library_books),
                count = totalBookCount,
                badgeContainer = MaterialTheme.colorScheme.primary,
                badgeContent = MaterialTheme.colorScheme.onPrimary,
                onSeeAll = seeAllCallback(totalBookCount, SearchResultCaps.BOOK, onSeeAll),
            )
        }
        gridItems(
            items = books,
            key = { hitKey(BOOK_KEY_PREFIX, it) },
            contentType = { CONTENT_TYPE_BOOK_CARD },
        ) { hit ->
            BookCard(
                cover = hit.toCoverModel(),
                onClick = { onResultClick(hit) },
                subtitle = hit.seriesName,
                duration = hit.formatDuration(),
                isHeld = hit.isHeld,
            )
        }
    }
}
