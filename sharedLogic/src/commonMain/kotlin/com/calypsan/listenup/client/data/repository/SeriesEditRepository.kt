package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.core.Failure
import com.calypsan.listenup.core.IODispatcher
import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.core.Success
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.client.data.local.db.SeriesDao
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.withContext

private val logger = KotlinLogging.logger {}

/**
 * Contract for series editing operations.
 *
 * Provides methods for modifying series metadata.
 * Changes are applied locally immediately; server propagation for series
 * edits is a Books-C concern and is not yet wired.
 */
interface SeriesEditRepositoryContract {
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

/**
 * Repository for series editing operations using an offline-first pattern.
 *
 * Edits are applied optimistically to Room and returned as success immediately.
 * Server propagation for series edits is a Books-C concern and is not yet wired.
 *
 * @property seriesDao Room DAO for series operations
 */
class SeriesEditRepository(
    private val seriesDao: SeriesDao,
) : SeriesEditRepositoryContract,
    com.calypsan.listenup.client.domain.repository.SeriesEditRepository {
    /**
     * Update series metadata.
     *
     * Applies the update optimistically to Room and returns success immediately.
     * Server propagation for series edits is a Books-C concern and is not yet wired.
     */
    override suspend fun updateSeries(
        seriesId: String,
        name: String?,
        description: String?,
    ): AppResult<Unit> =
        withContext(IODispatcher) {
            logger.debug { "Updating series (offline-first): $seriesId" }

            // Get existing series
            val existing = seriesDao.getById(seriesId)
            if (existing == null) {
                logger.error { "Series not found: $seriesId" }
                return@withContext Failure(Exception("Series not found: $seriesId"))
            }

            // Apply optimistic update
            val updated =
                existing.copy(
                    name = name ?: existing.name,
                    description = description ?: existing.description,
                    updatedAt = Timestamp.now(),
                )
            seriesDao.upsert(updated)

            logger.info { "Series updated locally: $seriesId" }
            Success(Unit)
        }
}
