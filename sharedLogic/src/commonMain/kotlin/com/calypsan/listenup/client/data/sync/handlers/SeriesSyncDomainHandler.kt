package com.calypsan.listenup.client.data.sync.handlers

import com.calypsan.listenup.api.sync.SeriesSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.SeriesEntity
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.sync.ClientSyncDomainRegistry
import com.calypsan.listenup.client.data.sync.SyncDomainHandler
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Client-side sync handler for the `series` domain (Books-B1).
 *
 * Applies server sync events into the Room `series` table. The handler owns a
 * series' identity fields (`name`); the book sync handler only bootstraps a
 * missing stub and never overwrites a row this handler owns.
 *
 * **Enrichment preservation.** `description`, `asin`, and cover imagery are
 * copied from the existing row — [SeriesSyncPayload] does not carry them, so a
 * B1 sync must never null Books-B2 enrichment data.
 *
 * `isOwnEcho` is ignored: B1 has no client-side series write path.
 *
 * Self-registers in [ClientSyncDomainRegistry] at construction.
 */
class SeriesSyncDomainHandler(
    private val database: ListenUpDatabase,
    private val transactionRunner: TransactionRunner,
    registry: ClientSyncDomainRegistry,
) : SyncDomainHandler<SeriesSyncPayload> {
    override val domainName: String = "series"
    override val payloadSerializer = SeriesSyncPayload.serializer()

    init {
        registry.register(this)
    }

    override suspend fun onEvent(
        event: SyncEvent<SeriesSyncPayload>,
        isOwnEcho: Boolean,
    ): AppResult<Unit> =
        transactionRunner.applyEventAtomically(domainName, event.id, logger) {
            when (event) {
                is SyncEvent.Created -> {
                    upsert(event.payload)
                }

                is SyncEvent.Updated -> {
                    upsert(event.payload)
                }

                is SyncEvent.Deleted -> {
                    database.seriesDao().softDelete(
                        id = SeriesId(event.id),
                        deletedAt = event.occurredAt,
                        revision = event.revision,
                    )
                }
            }
        }

    override suspend fun onCatchUpItem(
        item: SeriesSyncPayload,
        isTombstone: Boolean,
    ): AppResult<Unit> =
        transactionRunner.applyEventAtomically(domainName, item.id, logger) {
            if (isTombstone) {
                database.seriesDao().softDelete(
                    id = SeriesId(item.id),
                    deletedAt = item.deletedAt ?: item.updatedAt,
                    revision = item.revision,
                )
            } else {
                upsert(item)
            }
        }

    /**
     * Upsert the series row.
     *
     * Enrichment fields are applied from the wire payload when non-null; when null
     * (a B1-era event that predates enrichment), the existing row's value is
     * preserved so a B1 sync never zeroes Books-B2 data.
     */
    private suspend fun upsert(payload: SeriesSyncPayload) {
        val existing = database.seriesDao().getById(payload.id)
        database.seriesDao().upsert(
            SeriesEntity(
                id = SeriesId(payload.id),
                name = payload.name,
                sortName = payload.sortName,
                description = payload.description ?: existing?.description,
                asin = payload.asin ?: existing?.asin,
                coverPath = payload.coverPath ?: existing?.coverPath,
                coverBlurHash = payload.coverBlurHash ?: existing?.coverBlurHash,
                revision = payload.revision,
                deletedAt = payload.deletedAt,
                createdAt = Timestamp(payload.createdAt),
                updatedAt = Timestamp(payload.updatedAt),
            ),
        )
    }
}
