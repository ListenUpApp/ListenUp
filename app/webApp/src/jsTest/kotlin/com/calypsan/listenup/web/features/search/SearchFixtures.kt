package com.calypsan.listenup.web.features.search

import com.calypsan.listenup.client.domain.model.SearchFacets
import com.calypsan.listenup.client.domain.model.SearchHit
import com.calypsan.listenup.client.domain.model.SearchHitType
import com.calypsan.listenup.client.domain.model.SearchResult

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
): SearchHit = SearchHit(id = id, type = SearchHitType.CONTRIBUTOR, name = name)

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
    isOfflineResult: Boolean = false,
): SearchResult =
    SearchResult(
        query = query,
        total = total,
        tookMs = 0,
        hits = hits,
        facets = SearchFacets(),
        isOfflineResult = isOfflineResult,
    )
