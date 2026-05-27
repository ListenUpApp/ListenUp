package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.api.dto.SeriesUpdate
import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.core.SeriesId

/**
 * Client-side write surface for series editing.
 *
 * RPC-backed. SSE delivers authoritative state back via the SeriesSyncDomainHandler.
 */
interface SeriesEditRepository {
    /**
     * Applies the PATCH payload [patch] to the series identified by [id].
     *
     * Every non-null field on [patch] replaces the current value; null fields
     * leave existing state untouched. The server emits an SSE event with the
     * updated payload on success; clients update Room reactively.
     */
    suspend fun updateSeries(
        id: SeriesId,
        patch: SeriesUpdate,
    ): AppResult<Unit>

    /**
     * Hard-deletes all `book_series_memberships` junction rows referencing [id],
     * then soft-deletes the series row. The server emits SSE events for the
     * affected books and the series; clients update Room reactively.
     */
    suspend fun deleteSeries(id: SeriesId): AppResult<Unit>
}
