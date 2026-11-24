package com.calypsan.listenup.web.features.search

import androidx.compose.runtime.Composable
import com.calypsan.listenup.api.dto.image.ImageFrame
import com.calypsan.listenup.client.domain.model.SearchFacets
import com.calypsan.listenup.client.domain.model.SearchHit
import com.calypsan.listenup.client.domain.model.SearchHitType
import com.calypsan.listenup.client.domain.model.SearchResult
import com.calypsan.listenup.client.presentation.search.SearchUiState

/** Renders [SearchPage] with no-op callback defaults for specs. */
@Composable
@Suppress("LongParameterList")
internal fun testSearchPage(
    state: SearchUiState,
    onQueryChanged: (String) -> Unit = {},
    onToggleType: (SearchHitType) -> Unit = {},
    onClearTypes: () -> Unit = {},
    onOpenHit: (SearchHit) -> Unit = {},
    onRetry: () -> Unit = {},
    openableTypes: Set<SearchHitType> = SearchHitType.entries.toSet(),
    onSeeAll: (SearchHitType) -> Unit = {},
) {
    SearchPage(
        state = state,
        onQueryChanged = onQueryChanged,
        onToggleType = onToggleType,
        onClearTypes = onClearTypes,
        onOpenHit = onOpenHit,
        onRetry = onRetry,
        openableTypes = openableTypes,
        onSeeAll = onSeeAll,
    )
}

/** Renders [CommandPalette] with no-op callback defaults for specs. */
@Composable
@Suppress("LongParameterList")
internal fun testCommandPalette(
    state: SearchUiState,
    onQueryChanged: (String) -> Unit = {},
    onOpenHit: (SearchHit) -> Unit = {},
    openableTypes: Set<SearchHitType> = SearchHitType.entries.toSet(),
    highlighted: SearchHit? = null,
    onDismiss: () -> Unit = {},
    onSeeAll: (SearchHitType) -> Unit = {},
) {
    CommandPalette(
        state = state,
        onQueryChanged = onQueryChanged,
        onOpenHit = onOpenHit,
        openableTypes = openableTypes,
        highlighted = highlighted,
        onDismiss = onDismiss,
        onSeeAll = onSeeAll,
    )
}

/**
 * One search hit's worth of data — enough for a spec to drive the grouping, row and click
 * contracts without a database or FTS index behind them. Shared rather than duplicated, the same
 * call `ContributorFixtures.kt` makes for its own feature.
 */
internal fun bookHit(
    id: String,
    name: String,
    author: String? = null,
    duration: Long? = null,
    coverHash: String? = null,
): SearchHit =
    SearchHit(
        id = id,
        type = SearchHitType.BOOK,
        name = name,
        author = author,
        duration = duration,
        coverHash = coverHash,
    )

internal fun contributorHit(
    id: String,
    name: String,
    imagePath: String? = null,
    imageFrame: ImageFrame? = null,
): SearchHit =
    SearchHit(id = id, type = SearchHitType.CONTRIBUTOR, name = name, imagePath = imagePath, imageFrame = imageFrame)

internal fun seriesHit(
    id: String,
    name: String,
): SearchHit = SearchHit(id = id, type = SearchHitType.SERIES, name = name)

internal fun tagHit(
    id: String,
    name: String,
): SearchHit = SearchHit(id = id, type = SearchHitType.TAG, name = name)

/** A [SearchResult] over [hits], with [total] defaulting to the hit count rather than a made-up number. */
internal fun searchResult(
    query: String,
    hits: List<SearchHit>,
    total: Int = hits.size,
): SearchResult =
    SearchResult(
        query = query,
        total = total,
        tookMs = 0,
        hits = hits,
        facets = SearchFacets(),
    )
