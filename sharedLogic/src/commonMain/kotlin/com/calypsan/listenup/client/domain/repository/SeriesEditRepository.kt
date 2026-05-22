package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.core.AppResult

/**
 * Repository contract for series editing operations.
 *
 * Provides methods for modifying series metadata.
 * Changes are applied locally immediately; server propagation for series
 * edits is a Books-C concern and is not yet wired.
 *
 * Part of the domain layer - implementations live in the data layer.
 */
interface SeriesEditRepository {
    /**
     * Update series metadata.
     *
     * Applies update locally. Only non-null fields are updated (PATCH semantics).
     *
     * @param seriesId ID of the series to update
     * @param name New name (null = don't change)
     * @param description New description (null = don't change)
     * @return Result indicating success or failure
     */
    suspend fun updateSeries(
        seriesId: String,
        name: String?,
        description: String?,
    ): AppResult<Unit>
}
