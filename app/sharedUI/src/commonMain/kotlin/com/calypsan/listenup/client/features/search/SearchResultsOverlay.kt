package com.calypsan.listenup.client.features.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import com.calypsan.listenup.client.design.components.EmptyState
import com.calypsan.listenup.client.design.components.FullScreenLoadingIndicator
import com.calypsan.listenup.client.design.components.LoadFailed
import com.calypsan.listenup.client.design.components.PillChip
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.icons.ListenUpIcons
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.design.util.PlatformBackHandler
import com.calypsan.listenup.client.domain.model.MIN_SEARCH_QUERY_LENGTH
import com.calypsan.listenup.client.domain.model.SearchHit
import com.calypsan.listenup.client.domain.model.SearchHitType
import com.calypsan.listenup.client.domain.model.SearchResult
import com.calypsan.listenup.client.presentation.search.SearchUiState
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_tags
import listenup.composeapp.generated.resources.common_series
import listenup.composeapp.generated.resources.library_books
import listenup.composeapp.generated.resources.search_keep_typing
import listenup.composeapp.generated.resources.search_keep_typing_description
import listenup.composeapp.generated.resources.search_no_results_for_query
import listenup.composeapp.generated.resources.search_people
import listenup.composeapp.generated.resources.search_results_count_for
import listenup.composeapp.generated.resources.search_tab_all
import listenup.composeapp.generated.resources.search_try_a_different_search_term
import listenup.composeapp.generated.resources.shell_close_search
import org.jetbrains.compose.resources.stringResource

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
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Which single-type "See all" page is open, if any. Owned here (not a nav-stack entry) so the
    // overlay can render it in place of the grouped list and back out one level before collapsing.
    var seeAllType by rememberSeeAllType()

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
                        modifier = Modifier.padding(horizontal = Spacing.screenMargin, vertical = Spacing.sm),
                    )

                    TypeFilterRow(
                        selectedTypes = state.selectedTypes,
                        onToggle = onTypeFilterToggle,
                        onSelectAll = onClearTypeFilters,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )

                    when (state) {
                        is SearchUiState.Idle -> {
                        }

                        is SearchUiState.TooShort -> {
                            TooShortState(modifier = Modifier.weight(1f))
                        }

                        is SearchUiState.Searching -> {
                            LoadingState(modifier = Modifier.weight(1f))
                        }

                        is SearchUiState.Error -> {
                            LoadFailed(message = state.message, onRetry = onRetry, modifier = Modifier.weight(1f))
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
    val haptics = LocalHaptics.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = {
                    haptics.press()
                    onClose()
                },
            ) {
                Icon(
                    imageVector = ListenUpIcons.ArrowBackFilled,
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
            IconButton(
                onClick = {
                    haptics.press()
                    onClose()
                },
            ) {
                Icon(
                    imageVector = ListenUpIcons.CloseFilled,
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
                .padding(horizontal = Spacing.screenMargin),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PillChip(
            label = stringResource(Res.string.search_tab_all),
            onClick = onSelectAll,
            selected = selectedTypes.isEmpty(),
            selectionRole = Role.Checkbox,
            leadingIcon = ListenUpIcons.AppsFilled,
        )
        PillChip(
            label = stringResource(Res.string.library_books),
            onClick = { onToggle(SearchHitType.BOOK) },
            selected = SearchHitType.BOOK in selectedTypes,
            selectionRole = Role.Checkbox,
            leadingIcon = ListenUpIcons.BookFilled,
        )
        PillChip(
            label = stringResource(Res.string.search_people),
            onClick = { onToggle(SearchHitType.CONTRIBUTOR) },
            selected = SearchHitType.CONTRIBUTOR in selectedTypes,
            selectionRole = Role.Checkbox,
            leadingIcon = ListenUpIcons.PersonFilled,
        )
        PillChip(
            label = stringResource(Res.string.common_series),
            onClick = { onToggle(SearchHitType.SERIES) },
            selected = SearchHitType.SERIES in selectedTypes,
            selectionRole = Role.Checkbox,
            leadingIcon = ListenUpIcons.PlaylistPlayFilled,
        )
        PillChip(
            label = stringResource(Res.string.book_detail_tags),
            onClick = { onToggle(SearchHitType.TAG) },
            selected = SearchHitType.TAG in selectedTypes,
            selectionRole = Role.Checkbox,
            leadingIcon = ListenUpIcons.TagFilled,
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
        NoResultsState(query = query, modifier = modifier)
        return
    }
    val grouped = result.hits.groupBy { it.type }
    val books = grouped[SearchHitType.BOOK].orEmpty().distinctBy { it.id }
    val contributors = grouped[SearchHitType.CONTRIBUTOR].orEmpty().distinctBy { it.id }
    val series = grouped[SearchHitType.SERIES].orEmpty().distinctBy { it.id }
    val tags = grouped[SearchHitType.TAG].orEmpty().distinctBy { it.id }

    // Width signal: at medium+ width Books get a cover grid beside a People/Series/Tags rail.
    val isWide =
        currentWindowAdaptiveInfoV2().windowSizeClass.isWidthAtLeastBreakpoint(
            WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND,
        )

    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = stringResource(Res.string.search_results_count_for, result.total, query),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.screenMargin, vertical = Spacing.xs),
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

@Composable
internal fun LoadingState(modifier: Modifier = Modifier) {
    FullScreenLoadingIndicator(modifier = modifier)
}

/** A search for [query] returned zero hits — reuses the canonical [EmptyState] scaffolding. */
@Composable
internal fun NoResultsState(
    query: String,
    modifier: Modifier = Modifier,
) {
    EmptyState(
        title = stringResource(Res.string.search_no_results_for_query, query),
        subtitle = stringResource(Res.string.search_try_a_different_search_term),
        modifier = modifier,
    )
}

/**
 * [query] is below [MIN_SEARCH_QUERY_LENGTH], so no search ran. Reuses the same [EmptyState]
 * scaffolding as [NoResultsState] but with keep-typing copy and a search icon, so the two states
 * read as visually related while staying unmistakably distinct in meaning.
 */
@Composable
internal fun TooShortState(modifier: Modifier = Modifier) {
    EmptyState(
        title = stringResource(Res.string.search_keep_typing),
        subtitle = stringResource(Res.string.search_keep_typing_description, MIN_SEARCH_QUERY_LENGTH),
        modifier = modifier,
        icon = ListenUpIcons.SearchFilled,
    )
}
