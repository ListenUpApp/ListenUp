package com.calypsan.listenup.client.domain.model

import com.calypsan.listenup.client.core.DurationFormatter
import kotlin.time.Duration.Companion.milliseconds

/**
 * Shortest query the local search index can answer.
 *
 * The client's FTS5 tables are built with `tokenize='trigram'`, which indexes three-character
 * sequences and therefore **cannot match a shorter query at all** — not "matches nothing", but
 * "can never match". A query below this length must not be run: doing so returns an empty result
 * that is indistinguishable from a genuine miss, so the UI reports "no results" for something the
 * user could still complete by typing one more letter.
 *
 * It lives here, once, on purpose. This floor was previously copied into each caller as a private
 * constant, and when the tokenizer moved from `porter` (which had no such floor) to trigram, none
 * of the copies were updated — every search surface silently kept a stale value. A property of the
 * index belongs with the domain, not duplicated across its consumers.
 */
const val MIN_SEARCH_QUERY_LENGTH: Int = 3

/**
 * Type of search result.
 */
enum class SearchHitType {
    BOOK,
    CONTRIBUTOR,
    SERIES,
    TAG,
}

/**
 * A single search result hit.
 *
 * Contains enough information to render a result card and navigate
 * to the detail screen without additional database queries.
 */
data class SearchHit(
    val id: String,
    val type: SearchHitType,
    val name: String,
    val subtitle: String? = null,
    val author: String? = null,
    val narrator: String? = null,
    val seriesName: String? = null,
    val duration: Long? = null,
    val bookCount: Int? = null,
    val genreSlugs: List<String>? = null,
    val tags: List<String>? = null,
    val coverPath: String? = null,
    val coverHash: String? = null,
    val score: Float = 0f,
    val highlight: String? = null,
) {
    /**
     * Format duration as human-readable string.
     */
    fun formatDuration(): String? = duration?.let { DurationFormatter.hoursMinutes(it.milliseconds) }
}

/**
 * Facet count for filtering UI.
 */
data class FacetCount(
    val value: String,
    val count: Int,
)

/**
 * Facets returned with search results.
 *
 * Used to power dynamic filter chips (e.g., "Fantasy (47)").
 */
data class SearchFacets(
    val types: List<FacetCount> = emptyList(),
    val genres: List<FacetCount> = emptyList(),
    val authors: List<FacetCount> = emptyList(),
    val narrators: List<FacetCount> = emptyList(),
)

/**
 * Complete search result.
 */
data class SearchResult(
    val query: String,
    val total: Int,
    val tookMs: Long,
    val hits: List<SearchHit>,
    val facets: SearchFacets = SearchFacets(),
    val isOfflineResult: Boolean = false,
)
