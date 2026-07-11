package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.SeriesService
import com.calypsan.listenup.api.dto.SeriesUpdate
import com.calypsan.listenup.client.data.local.db.SeriesDao
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.sync.OfflineEditor
import com.calypsan.listenup.client.data.sync.domains.OutboxChannels
import com.calypsan.listenup.client.domain.repository.SeriesEditRepository
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.SeriesId

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
 * the server is their single write path back into Room. Both route through the
 * [channel], which bounds the call, self-heals the transport, and folds any
 * fault to a typed [AppResult.Failure], following the same pattern as
 * [BookEditRepositoryImpl].
 */
internal class SeriesEditRepositoryImpl(
    private val channel: RpcChannel<SeriesService>,
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

    override suspend fun deleteSeries(id: SeriesId): AppResult<Unit> = channel.call { it.deleteSeries(id) }

    override suspend fun mergeSeries(
        source: SeriesId,
        target: SeriesId,
    ): AppResult<Unit> = channel.call { it.mergeSeries(source, target) }
}
