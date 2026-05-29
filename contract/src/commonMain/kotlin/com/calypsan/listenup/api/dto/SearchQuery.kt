package com.calypsan.listenup.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A search request: the user's text plus optional book-scoping filters and a sort order.
 *
 * When [filters] is active or [sort] is not [SearchSort.Relevance], the result set is
 * scoped to books only (the filters/sort are book concepts). Otherwise the search is
 * federated across books, contributors, series, and tags.
 */
@Serializable
@SerialName("SearchQuery")
data class SearchQuery(
    /** User-supplied search text. Blank text yields empty results (never an error). */
    @SerialName("text") val text: String,
    /** Max hits per category, clamped server-side to `1..100`. */
    @SerialName("limit") val limit: Int = 20,
    /** Optional book-scoping filters. `null` means an unfiltered, federated search. */
    @SerialName("filters") val filters: SearchFilters? = null,
    /** Result ordering. Non-relevance sorts scope the result to books only. */
    @SerialName("sort") val sort: SearchSort = SearchSort.Relevance,
)

/**
 * Book-scoping filters. All fields are book attributes; applying any of them scopes
 * the search to books (contributors/series/tags have no genre/duration/year).
 */
@Serializable
@SerialName("SearchFilters")
data class SearchFilters(
    /** Match books tagged with ANY of these genre slugs (exact slug match). */
    @SerialName("genreSlugs") val genreSlugs: List<String> = emptyList(),
    /** Match books in this genre subtree, by materialized path prefix, e.g. "/fiction/fantasy". */
    @SerialName("genrePath") val genrePath: String? = null,
    /** Inclusive lower bound on book duration in seconds. */
    @SerialName("durationMinSeconds") val durationMinSeconds: Long? = null,
    /** Inclusive upper bound on book duration in seconds. */
    @SerialName("durationMaxSeconds") val durationMaxSeconds: Long? = null,
    /** Inclusive lower bound on publication year. */
    @SerialName("yearMin") val yearMin: Int? = null,
    /** Inclusive upper bound on publication year. */
    @SerialName("yearMax") val yearMax: Int? = null,
) {
    /** True when any filter constrains the result (drives the federated→books-only collapse). */
    val isActive: Boolean
        get() =
            genreSlugs.isNotEmpty() || genrePath != null ||
                durationMinSeconds != null || durationMaxSeconds != null ||
                yearMin != null || yearMax != null
}

/** Result ordering for search. Non-[Relevance] sorts apply to the books-only result. */
@Serializable
@SerialName("SearchSort")
enum class SearchSort {
    /** FTS5 BM25 relevance (default). */
    @SerialName("relevance")
    Relevance,

    /** Alphabetical by book sort-title. */
    @SerialName("title")
    Title,

    /** Most-recently-updated first. */
    @SerialName("recent")
    Recent,

    /** Shortest book duration first. */
    @SerialName("duration")
    Duration,
}
