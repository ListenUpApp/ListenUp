package com.calypsan.listenup.client.domain.model

/**
 * Lightweight series representation for search autocomplete.
 *
 * Used when editing book series to find existing series to link.
 * Contains only the minimum information needed for display and selection.
 */
data class SeriesSearchResult(
    val id: String,
    val name: String,
    /** Every book the library shows in this series and its sub-series. */
    val bookCount: Int,
    /** The series above this one, root first — shown as "in Cosmere › Mistborn". Empty at the top level. */
    val parentPath: List<String> = emptyList(),
)

/**
 * Response from series search operations.
 *
 * Contains the search results along with metadata about the search source.
 * The `isOfflineResult` flag indicates if results came from local FTS
 * (offline fallback) rather than the server.
 */
data class SeriesSearchResponse(
    val series: List<SeriesSearchResult>,
    val isOfflineResult: Boolean,
    val tookMs: Long,
)
