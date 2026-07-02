package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.result.AppResult

/**
 * Contract interface for series API operations.
 *
 * Handles series search and updates.
 */
internal interface SeriesApiContract {
    /**
     * Search series for autocomplete during book editing.
     *
     * Uses server-side Bleve search for O(log n) performance with:
     * - Prefix matching ("mist" → "Mistborn")
     * - Word matching
     * - Fuzzy matching for typo tolerance
     *
     * @param query Search query (min 2 characters recommended)
     * @param limit Maximum results to return (default 10, max 50)
     * @return Result containing list of matching series
     */
    suspend fun searchSeries(
        query: String,
        limit: Int = 10,
    ): AppResult<List<SeriesSearchResult>>

    /**
     * Update series metadata (PATCH semantics).
     *
     * Only fields present in the request are updated:
     * - null field = don't change
     * - empty string = clear the field
     *
     * @param seriesId Series to update
     * @param request Fields to update
     * @return Result containing the updated series
     */
    suspend fun updateSeries(
        seriesId: String,
        request: SeriesUpdateRequest,
    ): AppResult<SeriesEditResponse>
}

/**
 * Series search result for autocomplete.
 *
 * Lightweight representation returned by series search endpoint.
 * Used when editing book series to find existing series to link.
 */
internal data class SeriesSearchResult(
    val id: String,
    val name: String,
    val bookCount: Int,
)

/**
 * Request to update a series' metadata (PATCH semantics).
 */
internal data class SeriesUpdateRequest(
    val name: String? = null,
    val description: String? = null,
)

/**
 * Response from series edit operations.
 */
internal data class SeriesEditResponse(
    val id: String,
    val name: String,
    val description: String? = null,
    val updatedAt: String,
)
