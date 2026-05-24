package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.SeriesService
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.SeriesSyncPayload
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.SeriesRepository

/**
 * Thin [SeriesService] implementation.
 *
 * Translates cache-miss read requests for series entities from the wire
 * contract to repository calls. The implementation is read-only: all writes
 * go through the scanner via [SeriesRepository.resolveOrCreate].
 *
 * This service is not user-scoped — it carries no [com.calypsan.listenup.server.auth.PrincipalProvider]
 * because series reads are not per-user. Auth is enforced at the route layer
 * (JWT gate in Application.kt).
 */
internal class SeriesServiceImpl(
    private val seriesRepo: SeriesRepository,
    private val bookRepo: BookRepository,
) : SeriesService {
    override suspend fun getSeries(id: SeriesId): AppResult<SeriesSyncPayload?> =
        AppResult.Success(seriesRepo.findById(id.value))

    override suspend fun listBooksBySeries(id: SeriesId): AppResult<List<BookSyncPayload>> =
        AppResult.Success(bookRepo.findBySeries(id))
}
