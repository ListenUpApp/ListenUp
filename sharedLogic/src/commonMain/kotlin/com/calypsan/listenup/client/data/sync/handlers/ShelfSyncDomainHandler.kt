package com.calypsan.listenup.client.data.sync.handlers

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.ShelfSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.ShelfEntity
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.sync.ClientSyncDomainRegistry
import com.calypsan.listenup.client.data.sync.SyncDomainHandler
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Client-side sync handler for the `shelves` domain (Shelves — Room v26).
 *
 * Applies server sync events into the Room `shelves` table. Shelves are user-scoped
 * own-data: the substrate already scopes pull/firehose queries to the caller's rows,
 * so this is a plain [SyncDomainHandler] — no [com.calypsan.listenup.client.data.sync.AccessFilteredSyncHandler]
 * access-reconcile (the [GenreSyncDomainHandler] precedent).
 *
 * Shelf rows carry the full wire payload on [SyncEvent.Created] and [SyncEvent.Updated];
 * [SyncEvent.Deleted] events soft-delete the row via
 * [com.calypsan.listenup.client.data.local.db.ShelfDao.softDelete].
 *
 * `bookCount` is JOIN-derived (never stored), so the handler maps only the substrate
 * fields — drift is impossible by construction.
 *
 * `isOwnEcho` is passed through but not acted on: `@Upsert` is idempotent, so re-applying
 * a server echo of the client's own write produces the same row.
 *
 * Self-registers in [ClientSyncDomainRegistry] at construction.
 */
internal class ShelfSyncDomainHandler(
    private val database: ListenUpDatabase,
    private val transactionRunner: TransactionRunner,
    registry: ClientSyncDomainRegistry,
) : SyncDomainHandler<ShelfSyncPayload> {
    override val domainName: String = "shelves"
    override val payloadSerializer = ShelfSyncPayload.serializer()

    override fun syncId(item: ShelfSyncPayload): String = item.id

    init {
        registry.register(this)
    }

    override suspend fun onEvent(
        event: SyncEvent<ShelfSyncPayload>,
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
                    database.shelfDao().softDelete(
                        id = event.id,
                        deletedAt = event.occurredAt,
                        revision = event.revision,
                    )
                }
            }
        }

    override suspend fun onCatchUpItem(
        item: ShelfSyncPayload,
        isTombstone: Boolean,
    ): AppResult<Unit> =
        transactionRunner.applyEventAtomically(domainName, item.id, logger) {
            if (isTombstone) {
                database.shelfDao().softDelete(
                    id = item.id,
                    deletedAt = item.deletedAt ?: item.updatedAt,
                    revision = item.revision,
                )
            } else {
                upsert(item)
            }
        }

    override suspend fun localDigestRows(maxRevision: Long): List<Pair<String, Long>> =
        database.shelfDao().digestRows(maxRevision).map { it.id to it.revision }

    private suspend fun upsert(payload: ShelfSyncPayload) {
        database.shelfDao().upsert(
            ShelfEntity(
                id = payload.id,
                name = payload.name,
                description = payload.description,
                isPrivate = payload.isPrivate,
                revision = payload.revision,
                deletedAt = payload.deletedAt,
                updatedAt = payload.updatedAt,
                createdAt = payload.createdAt,
            ),
        )
    }
}
