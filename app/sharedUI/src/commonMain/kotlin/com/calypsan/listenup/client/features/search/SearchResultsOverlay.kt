@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.calypsan.listenup.client.features.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.window.core.layout.WindowSizeClass
import com.calypsan.listenup.client.design.components.BookCoverImage
import com.calypsan.listenup.client.design.components.ContentRow
import com.calypsan.listenup.client.design.components.FullScreenLoadingIndicator
import com.calypsan.listenup.client.design.components.PillChip
import com.calypsan.listenup.client.design.components.ScallopBadge
import com.calypsan.listenup.client.design.components.toCoverModel
import com.calypsan.listenup.client.design.components.highlightMatch
import com.calypsan.listenup.client.design.util.PlatformBackHandler
import com.calypsan.listenup.client.features.library.BookCard
import com.calypsan.listenup.client.domain.model.SearchHit
import com.calypsan.listenup.client.domain.model.SearchHitType
import com.calypsan.listenup.client.domain.model.SearchResult
import com.calypsan.listenup.client.presentation.search.SearchResultCaps
import com.calypsan.listenup.client.presentation.search.SearchUiState
import com.calypsan.listenup.client.presentation.search.SeeAllSearchUiState
import com.calypsan.listenup.client.presentation.search.SeeAllSearchViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_tags
import listenup.composeapp.generated.resources.book_edit_showing_offline_results
import listenup.composeapp.generated.resources.common_series
import listenup.composeapp.generated.resources.genre_book_count
import listenup.composeapp.generated.resources.genre_books_count
import listenup.composeapp.generated.resources.library_books
import listenup.composeapp.generated.resources.search_cover_for
import listenup.composeapp.generated.resources.search_no_results_for_query
import listenup.composeapp.generated.resources.search_people
import listenup.composeapp.generated.resources.search_results_count_for
import listenup.composeapp.generated.resources.search_see_all
import listenup.composeapp.generated.resources.search_tab_all
import listenup.composeapp.generated.resources.search_try_a_different_search_term
import listenup.composeapp.generated.resources.shell_close_search

// Stable lazy-list item keys, shared across the compact list, the wide rail, and the See-all page
// so a single hit keeps the same identity wherever it renders.
private const val BOOKS_HEADER_KEY = "books_header"
private const val CONTRIBUTORS_HEADER_KEY = "contributors_header"
private const val SERIES_HEADER_KEY = "series_header"
private const val TAGS_HEADER_KEY = "tags_header"
private const val TAGS_FLOW_KEY = "tags_flow"
private const val BOOK_KEY_PREFIX = "book_"
private const val CONTRIBUTOR_KEY_PREFIX = "contributor_"
private const val SERIES_KEY_PREFIX = "series_"

/** Stable lazy-list item key for a hit, namespaced by [prefix] so types never collide. */
private fun hitKey(
    prefix: String,
    hit: SearchHit,
): String = "$prefix${hit.id}"

/**
 * Full-screen overlay for search results.
 *
 * Displays federated results grouped by type (Books, People, Series, Tags) in the Material 3
 * Expressive language: a docked pill search bar, big filled-coral scope chips, scalloped count
 * badges on each group header, and live query-match highlighting on result labels.
 * [isExpanded] is owned by the enclosing screen (search bar) and combined here with `state.query`
 * to drive entry/exit animation.
 */
@Composable
fun SearchResultsOverlay(
    state: SearchUiState,
    isExpanded: Boolean,
    onClose: () -> Unit,
    onResultClick: (SearchHit) -> Unit,
    onTypeFilterToggle: (SearchHitType) -> Unit,
    onClearTypeFilters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Which single-type "See all" page is open, if any. Owned here (not a nav-stack entry) so the
    // overlay can render it in place of the grouped list and back out one level before collapsing.
    var seeAllType by remember { mutableStateOf<SearchHitType?>(null) }

    // Reset the See-all page whenever the overlay closes, so re-opening search starts on the grouped
    // list rather than a stale single-type page.
    LaunchedEffect(isExpanded) {
        if (!isExpanded) seeAllType = null
    }

    AnimatedVisibility(
        visible = isExpanded && state.query.isNotBlank(),
        enter = fadeIn() + slideInVertically(),
        exit = fadeOut() + slideOutVertically(),
        modifier = modifier,
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            // System back closes the See-all page first (back out one level), then AppShell's own
            // handler collapses search. Both layers are active while the overlay is up.
            PlatformBackHandler(enabled = seeAllType != null) { seeAllType = null }

            val openType = seeAllType
            if (openType != null) {
                SearchSeeAllPage(
                    query = state.query,
                    type = openType,
                    onBack = { seeAllType = null },
                    onResultClick = onResultClick,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    // The overlay covers the shell header (and its back arrow), so it carries its own
                    // back affordance — the visible counterpart to the system-back handler in AppShell.
                    SearchPillBar(
                        query = state.query,
                        onClose = onClose,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )

                    TypeFilterRow(
                        selectedTypes = state.selectedTypes,
                        onToggle = onTypeFilterToggle,
                        onSelectAll = onClearTypeFilters,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )

                    if (state is SearchUiState.Results && state.result.isOfflineResult) {
                        OfflineIndicator(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
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
                                onSeeAll = { type -> seeAllType = type },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The docked Expressive pill search bar. Reflects the live [query]; both the leading back arrow
 * and the trailing clear button collapse the overlay back to the shell header for re-editing.
 */
@Composable
private fun SearchPillBar(
    query: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
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
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(Res.string.shell_close_search),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TypeFilterRow(
    selectedTypes: Set<SearchHitType>,
    onToggle: (SearchHitType) -> Unit,
    onSelectAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PillChip(
            label = stringResource(Res.string.search_tab_all),
            onClick = onSelectAll,
            selected = selectedTypes.isEmpty(),
            leadingIcon = Icons.Default.Apps,
        )
        PillChip(
            label = stringResource(Res.string.library_books),
            onClick = { onToggle(SearchHitType.BOOK) },
            selected = SearchHitType.BOOK in selectedTypes,
            leadingIcon = Icons.Default.Book,
        )
        PillChip(
            label = stringResource(Res.string.search_people),
            onClick = { onToggle(SearchHitType.CONTRIBUTOR) },
            selected = SearchHitType.CONTRIBUTOR in selectedTypes,
            leadingIcon = Icons.Default.Person,
        )
        PillChip(
            label = stringResource(Res.string.common_series),
            onClick = { onToggle(SearchHitType.SERIES) },
            selected = SearchHitType.SERIES in selectedTypes,
            leadingIcon = Icons.AutoMirrored.Filled.PlaylistPlay,
        )
        PillChip(
            label = stringResource(Res.string.book_detail_tags),
            onClick = { onToggle(SearchHitType.TAG) },
            selected = SearchHitType.TAG in selectedTypes,
            leadingIcon = Icons.Default.Tag,
        )
    }
}

@Composable
private fun ResultsContent(
    result: SearchResult,
    query: String,
    onResultClick: (SearchHit) -> Unit,
    onSeeAll: (SearchHitType) -> Unit,
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

    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = stringResource(Res.string.search_results_count_for, result.total, query),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = if (isWide) 24.dp else 16.dp, vertical = 4.dp),
        )
        if (isWide) {
            WideSearchResults(
                books = books,
                contributors = contributors,
                series = series,
                tags = tags,
                query = query,
                onResultClick = onResultClick,
                onSeeAll = onSeeAll,
                modifier = Modifier.weight(1f),
            )
        } else {
            SearchResultsList(
                books = books,
                contributors = contributors,
                series = series,
                tags = tags,
                query = query,
                onResultClick = onResultClick,
                onSeeAll = onSeeAll,
                modifier = Modifier.weight(1f),
            )
        }
    }
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
    query: String,
    onResultClick: (SearchHit) -> Unit,
    onSeeAll: (SearchHitType) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (books.isNotEmpty()) {
            item(key = BOOKS_HEADER_KEY) {
                GroupHeader(
                    title = stringResource(Res.string.library_books),
                    count = books.size,
                    badgeContainer = MaterialTheme.colorScheme.primary,
                    badgeContent = MaterialTheme.colorScheme.onPrimary,
                    onSeeAll = seeAllCallback(books.size, SearchResultCaps.BOOK) { onSeeAll(SearchHitType.BOOK) },
                )
            }
            items(books.take(SearchResultCaps.BOOK), key = { hitKey(BOOK_KEY_PREFIX, it) }) { hit ->
                BookResultRow(hit = hit, query = query, onClick = { onResultClick(hit) })
            }
        }

        contributorGroup(contributors, query, onResultClick, onSeeAll)
        seriesGroup(series, query, onResultClick, onSeeAll)
        tagGroup(tags, query, onResultClick)
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
    item(key = CONTRIBUTORS_HEADER_KEY) {
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
    items(contributors.take(SearchResultCaps.CONTRIBUTOR), key = { hitKey(CONTRIBUTOR_KEY_PREFIX, it) }) { hit ->
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
    item(key = SERIES_HEADER_KEY) {
        GroupHeader(
            title = stringResource(Res.string.common_series),
            count = series.size,
            badgeContainer = MaterialTheme.colorScheme.secondaryContainer,
            badgeContent = MaterialTheme.colorScheme.onSecondaryContainer,
            onSeeAll = seeAllCallback(series.size, SearchResultCaps.SERIES) { onSeeAll(SearchHitType.SERIES) },
        )
    }
    items(series.take(SearchResultCaps.SERIES), key = { hitKey(SERIES_KEY_PREFIX, it) }) { hit ->
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
    item(key = TAGS_HEADER_KEY) {
        GroupHeader(
            title = stringResource(Res.string.book_detail_tags),
            count = tags.size,
            badgeContainer = MaterialTheme.colorScheme.primaryContainer,
            badgeContent = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
    item(key = TAGS_FLOW_KEY) {
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
    query: String,
    onResultClick: (SearchHit) -> Unit,
    onSeeAll: (SearchHitType) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxSize().padding(horizontal = 24.dp),
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
                contributorGroup(contributors, query, onResultClick, onSeeAll)
                seriesGroup(series, query, onResultClick, onSeeAll)
                tagGroup(tags, query, onResultClick)
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
        item(key = BOOKS_HEADER_KEY, span = { GridItemSpan(maxLineSpan) }) {
            GroupHeader(
                title = stringResource(Res.string.library_books),
                count = totalBookCount,
                badgeContainer = MaterialTheme.colorScheme.primary,
                badgeContent = MaterialTheme.colorScheme.onPrimary,
                onSeeAll = seeAllCallback(totalBookCount, SearchResultCaps.BOOK, onSeeAll),
            )
        }
        gridItems(books, key = { hitKey(BOOK_KEY_PREFIX, it) }) { hit ->
            BookCard(
                cover = hit.toCoverModel(),
                onClick = { onResultClick(hit) },
                subtitle = hit.seriesName,
                duration = hit.formatDuration(),
            )
        }
    }
}

/**
 * Expressive group header: an emphasized title with a scalloped cookie count badge tinted to
 * the group's container role. When the group is capped in the main results view, [onSeeAll] is
 * non-null and a trailing "See all" affordance opens the full single-type page.
 */
@Composable
private fun GroupHeader(
    title: String,
    count: Int,
    badgeContainer: Color,
    badgeContent: Color,
    modifier: Modifier = Modifier,
    onSeeAll: (() -> Unit)? = null,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLargeEmphasized,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        ScallopBadge(size = 34.dp, containerColor = badgeContainer) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.ExtraBold,
                color = badgeContent,
            )
        }
        if (onSeeAll != null) {
            Spacer(modifier = Modifier.weight(1f))
            SeeAllAction(onClick = onSeeAll)
        }
    }
}

/** Trailing "See all" affordance in a capped group header: a label plus a forward chevron. */
@Composable
private fun SeeAllAction(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.search_see_all),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * Full-bleed single-type "See all" page, reached from a capped group's header in the main results
 * view. Shows a back-arrow top bar with the type's title, then the complete list of that type's
 * hits rendered with the same row composables the overlay uses. Binds its own
 * [SeeAllSearchViewModel] and loads the full list for [query] + [type] on entry; result taps route
 * through [onResultClick] (the same nav path the overlay threads), so AppShell stays the single
 * navigation owner.
 */
@Composable
private fun SearchSeeAllPage(
    query: String,
    type: SearchHitType,
    onBack: () -> Unit,
    onResultClick: (SearchHit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: SeeAllSearchViewModel = koinViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(query, type) {
        viewModel.load(query, type)
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            SeeAllTopBar(title = seeAllTitle(type), onBack = onBack)

            when (val current = state) {
                is SeeAllSearchUiState.Idle,
                is SeeAllSearchUiState.Loading,
                -> {
                    LoadingState(modifier = Modifier.weight(1f))
                }

                is SeeAllSearchUiState.Error -> {
                    ErrorState(message = current.message, modifier = Modifier.weight(1f))
                }

                is SeeAllSearchUiState.Results -> {
                    if (current.hits.isEmpty()) {
                        EmptyState(query = query, modifier = Modifier.weight(1f))
                    } else {
                        SeeAllList(
                            type = type,
                            hits = current.hits,
                            query = query,
                            onResultClick = onResultClick,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/** Top bar for the See-all page: a back arrow and the type's title. */
@Composable
private fun SeeAllTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(Res.string.shell_close_search),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmallEmphasized,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * The full list for one type. Tags get a wrapping pill flow; the other types reuse the same row
 * composables as the main overlay. A capped book/series/contributor list reads well at expanded
 * width inside a width-constrained column.
 */
@Composable
private fun SeeAllList(
    type: SearchHitType,
    hits: List<SearchHit>,
    query: String,
    onResultClick: (SearchHit) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (type) {
            SearchHitType.BOOK -> {
                items(hits, key = { hitKey(BOOK_KEY_PREFIX, it) }) { hit ->
                    BookResultRow(hit = hit, query = query, onClick = { onResultClick(hit) })
                }
            }

            SearchHitType.CONTRIBUTOR -> {
                items(hits, key = { hitKey(CONTRIBUTOR_KEY_PREFIX, it) }) { hit ->
                    PersonResultRow(hit = hit, query = query, onClick = { onResultClick(hit) })
                }
            }

            SearchHitType.SERIES -> {
                items(hits, key = { hitKey(SERIES_KEY_PREFIX, it) }) { hit ->
                    SeriesResultRow(hit = hit, query = query, onClick = { onResultClick(hit) })
                }
            }

            SearchHitType.TAG -> {
                item(key = TAGS_FLOW_KEY) {
                    TagFlow(tags = hits, query = query, onResultClick = onResultClick)
                }
            }
        }
    }
}

/** The localized page title for a single-type See-all page. */
@Composable
private fun seeAllTitle(type: SearchHitType): String =
    when (type) {
        SearchHitType.BOOK -> stringResource(Res.string.library_books)
        SearchHitType.CONTRIBUTOR -> stringResource(Res.string.search_people)
        SearchHitType.SERIES -> stringResource(Res.string.common_series)
        SearchHitType.TAG -> stringResource(Res.string.book_detail_tags)
    }

@Composable
private fun BookResultRow(
    hit: SearchHit,
    query: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ContentRow(onClick = onClick, modifier = modifier) {
        BookCoverImage(
            bookId = hit.id,
            coverPath = hit.coverPath,
            coverHash = hit.coverHash,
            contentDescription = stringResource(Res.string.search_cover_for, hit.name),
            contentScale = ContentScale.Crop,
            modifier =
                Modifier
                    .size(60.dp)
                    .clip(MaterialTheme.shapes.small),
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = highlightMatch(hit.name, query),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            hit.author?.let { author ->
                Text(
                    text = highlightMatch(author, query),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        hit.formatDuration()?.let { duration ->
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = duration,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PersonResultRow(
    hit: SearchHit,
    query: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ContentRow(onClick = onClick, modifier = modifier) {
        Box(
            modifier =
                Modifier
                    .size(52.dp)
                    // A circular icon badge; keep it round now that extraLarge is a bounded radius.
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = highlightMatch(hit.name, query),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            hit.bookCount?.let { count ->
                Text(
                    text = bookCountLabel(count),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SeriesResultRow(
    hit: SearchHit,
    query: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ContentRow(onClick = onClick, modifier = modifier) {
        ScallopBadge(size = 52.dp, containerColor = MaterialTheme.colorScheme.tertiaryContainer) {
            Icon(
                Icons.AutoMirrored.Filled.PlaylistPlay,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = highlightMatch(hit.name, query),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            hit.bookCount?.let { count ->
                Text(
                    text = bookCountLabel(count),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Tag hits as a wrapping row of Expressive pills, each highlighting the matched query. */
@Composable
private fun TagFlow(
    tags: List<SearchHit>,
    query: String,
    onResultClick: (SearchHit) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        tags.forEach { hit ->
            Surface(
                onClick = { onResultClick(hit) },
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(
                    modifier = Modifier.height(42.dp).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Tag,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = highlightMatch(hit.name, query),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun bookCountLabel(count: Int): String =
    if (count == 1) {
        stringResource(Res.string.genre_book_count, count)
    } else {
        stringResource(Res.string.genre_books_count, count)
    }

@Composable
private fun OfflineIndicator(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
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
