package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.result.AppResult

/**
 * Contract interface for series API operations.
 *
 * Handles series search and updates.
 */
internal interface SeriesApiContract {
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
