package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.core.AppResult

/**
 * Repository contract for series editing operations.
 *
 * Provides methods for modifying series metadata.
 * Uses offline-first pattern: changes are applied locally immediately
 * and queued for sync to server.
 *
 * Part of the domain layer - implementations live in the data layer.
 */
interface SeriesEditRepository {
    /**
     * Update series metadata.
     *
     * Applies update locally and queues for server sync.
     * Only non-null fields are updated (PATCH semantics).
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
