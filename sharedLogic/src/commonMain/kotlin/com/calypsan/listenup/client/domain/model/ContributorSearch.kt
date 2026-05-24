package com.calypsan.listenup.client.domain.model

/**
 * Lightweight contributor representation for search autocomplete.
 *
 * Used when editing book contributors to find existing contributors to link.
 * Contains only the minimum information needed for display and selection.
 */
data class ContributorSearchResult(
    val id: String,
    val name: String,
    val bookCount: Int,
)

/**
 * Response from contributor search operations.
 *
 * Contains the search results along with metadata about the search source.
 * The `isOfflineResult` flag indicates if results came from local FTS
 * (offline fallback) rather than the server.
 */
data class ContributorSearchResponse(
    val contributors: List<ContributorSearchResult>,
    val isOfflineResult: Boolean,
    val tookMs: Long,
)
