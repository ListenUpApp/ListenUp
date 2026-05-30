package com.calypsan.listenup.client.data.sync.handlers

import com.calypsan.listenup.api.sync.GenreSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.client.data.local.db.GenreEntity
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.sync.ClientSyncDomainRegistry
import com.calypsan.listenup.client.data.sync.SyncDomainHandler
import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.core.Timestamp
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Client-side sync handler for the `genres` domain.
 *
 * Applies server sync events into the Room `genres` table. Tree reads consult
 * Room directly via [com.calypsan.listenup.client.data.local.db.GenreDao]; this
 * handler is the only writer the sync engine ever invokes for genre rows.
 *
 * On `SyncEvent.Created` / `SyncEvent.Updated`: upsert the full payload —
 * server-authoritative for every column. On `SyncEvent.Deleted`: soft-delete
 * via [com.calypsan.listenup.client.data.local.db.GenreDao.softDelete]. The
 * row stays so revision bookkeeping survives; reads filter `deletedAt IS NULL`.
 *
 * Self-registers in [ClientSyncDomainRegistry] at construction.
 */
class GenreSyncDomainHandler(
    private val database: ListenUpDatabase,
    private val transactionRunner: TransactionRunner,
    registry: ClientSyncDomainRegistry,
) : SyncDomainHandler<GenreSyncPayload> {
    override val domainName: String = "genres"
    override val payloadSerializer = GenreSyncPayload.serializer()

    override fun syncId(item: GenreSyncPayload): String = item.id

    init {
        registry.register(this)
    }

    override suspend fun onEvent(
        event: SyncEvent<GenreSyncPayload>,
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
                    tombstone(
                        id = event.id,
                        deletedAt = event.occurredAt,
                        revision = event.revision,
                    )
                }
            }
        }

    override suspend fun onCatchUpItem(
        item: GenreSyncPayload,
        isTombstone: Boolean,
    ): AppResult<Unit> =
        transactionRunner.applyEventAtomically(domainName, item.id, logger) {
            if (isTombstone) {
                tombstone(
                    id = item.id,
                    deletedAt = item.deletedAt ?: item.updatedAt,
                    revision = item.revision,
                )
            } else {
                upsert(item)
            }
        }

    /** Soft-delete the genre row (column update; row stays for revision bookkeeping). */
    private suspend fun tombstone(
        id: String,
        deletedAt: Long,
        revision: Long,
    ) {
        database.genreDao().softDelete(id = id, deletedAt = deletedAt, revision = revision)
    }

    /** Upsert the genre row from the server-authoritative payload. */
    private suspend fun upsert(payload: GenreSyncPayload) {
        database.genreDao().upsert(
            GenreEntity(
                id = payload.id,
                name = payload.name,
                slug = payload.slug,
                path = payload.path,
                parentId = payload.parentId,
                depth = payload.depth,
                sortOrder = payload.sortOrder,
                revision = payload.revision,
                deletedAt = payload.deletedAt,
                createdAt = Timestamp(payload.createdAt),
                updatedAt = Timestamp(payload.updatedAt),
            ),
        )
    }
}
