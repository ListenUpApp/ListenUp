package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.dto.SeriesUpdate
import com.calypsan.listenup.api.result.AppResult as WireAppResult
import com.calypsan.listenup.client.data.local.db.SeriesDao
import com.calypsan.listenup.client.data.remote.SeriesRpcFactory
import com.calypsan.listenup.client.data.sync.OfflineEditor
import com.calypsan.listenup.client.data.sync.domains.OutboxChannels
import com.calypsan.listenup.client.domain.repository.SeriesEditRepository
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.client.core.error.ErrorMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException

private val logger = KotlinLogging.logger {}

/**
 * Offline-first series editor.
 *
 * [updateSeries] writes the patch into Room immediately and enqueues a durable
 * pending op (the same outbox the playback-position writes use), so an edit made
 * offline persists and replays on reconnect rather than failing with a
 * [com.calypsan.listenup.api.error.ServerConnectError]. The authoritative state
 * still arrives via the SSE sync engine and reconciles through
 * [com.calypsan.listenup.client.data.sync.domains.seriesDomain].
 *
 * [deleteSeries] and [mergeSeries] stay pure RPC dispatchers — the SSE echo from
 * the server is their single write path back into Room. Wire [WireAppResult]
 * values returned by the RPC service are converted to the client-layer
 * [AppResult] at this boundary, following the same pattern as
 * [BookEditRepositoryImpl].
 */
internal class SeriesEditRepositoryImpl(
    private val seriesRpcFactory: SeriesRpcFactory,
    private val seriesDao: SeriesDao,
    private val offlineEditor: OfflineEditor,
) : SeriesEditRepository {
    override suspend fun updateSeries(
        id: SeriesId,
        patch: SeriesUpdate,
    ): AppResult<Unit> =
        offlineEditor.edit(OutboxChannels.Series, id.value, patch) {
            seriesDao.getById(id.value)?.let { existing ->
                seriesDao.upsert(
                    existing.copy(
                        name = patch.name ?: existing.name,
                        sortName = patch.sortName ?: existing.sortName,
                        description = patch.description ?: existing.description,
                        coverPath = patch.coverPath ?: existing.coverPath,
                        asin = patch.asin ?: existing.asin,
                        // revision + updatedAt deliberately untouched.
                    ),
                )
            }
        }

    override suspend fun deleteSeries(id: SeriesId): AppResult<Unit> =
        rpcCallUnit { seriesRpcFactory.seriesService().deleteSeries(id) }

    override suspend fun mergeSeries(
        source: SeriesId,
        target: SeriesId,
    ): AppResult<Unit> = rpcCallUnit { seriesRpcFactory.seriesService().mergeSeries(source, target) }

    /**
     * Run an RPC call that returns [Unit], converting [WireAppResult] → [AppResult].
     * Re-throws [CancellationException]; all other throwables become [AppResult.Failure]
     * via [ErrorMapper].
     */
    private suspend fun rpcCallUnit(block: suspend () -> WireAppResult<Unit>): AppResult<Unit> =
        try {
            when (val result = block()) {
                is WireAppResult.Success -> AppResult.Success(Unit)
                is WireAppResult.Failure -> AppResult.Failure(result.error)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.warn(e) { "Series edit RPC failed" }
            AppResult.Failure(ErrorMapper.map(e))
        }
}
