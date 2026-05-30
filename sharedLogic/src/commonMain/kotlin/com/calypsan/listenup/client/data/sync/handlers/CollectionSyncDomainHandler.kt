package com.calypsan.listenup.client.data.sync.handlers

import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.client.data.local.db.CollectionEntity
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.sync.AccessFilteredSyncHandler
import com.calypsan.listenup.client.data.sync.ClientSyncDomainRegistry
import com.calypsan.listenup.client.data.sync.SyncDomainHandler
import com.calypsan.listenup.core.AppResult
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Client-side sync handler for the `collections` domain (Collections — Room v24).
 *
 * Applies server sync events into the Room `collections` table. Collection rows carry
 * the full wire payload on [SyncEvent.Created] and [SyncEvent.Updated]; [SyncEvent.Deleted]
 * events soft-delete the row via [com.calypsan.listenup.client.data.local.db.CollectionDao.softDelete].
 *
 * `bookCount` is JOIN-derived (never stored), so the handler maps only the substrate
 * fields — drift is impossible by construction.
 *
 * `isOwnEcho` is passed through but not acted on: `@Upsert` is idempotent, so re-applying
 * a server echo of the client's own write produces the same row.
 *
 * Self-registers in [ClientSyncDomainRegistry] at construction.
 */
class CollectionSyncDomainHandler(
    private val database: ListenUpDatabase,
    private val transactionRunner: TransactionRunner,
    registry: ClientSyncDomainRegistry,
) : SyncDomainHandler<CollectionSyncPayload>,
    AccessFilteredSyncHandler {
    override val domainName: String = "collections"
    override val payloadSerializer = CollectionSyncPayload.serializer()

    override fun syncId(item: CollectionSyncPayload): String = item.id

    init {
        registry.register(this)
    }

    override suspend fun localLiveIds(): Set<String> = database.collectionDao().liveIds().toSet()

    override suspend fun pruneTo(
        accessibleIds: Set<String>,
        now: Long,
    ) = database.collectionDao().tombstoneNotIn(accessibleIds, now)

    override suspend fun onEvent(
        event: SyncEvent<CollectionSyncPayload>,
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
                    database.collectionDao().softDelete(
                        id = event.id,
                        deletedAt = event.occurredAt,
                        revision = event.revision,
                    )
                }
            }
        }

    override suspend fun onCatchUpItem(
        item: CollectionSyncPayload,
        isTombstone: Boolean,
    ): AppResult<Unit> =
        transactionRunner.applyEventAtomically(domainName, item.id, logger) {
            if (isTombstone) {
                database.collectionDao().softDelete(
                    id = item.id,
                    deletedAt = item.deletedAt ?: item.updatedAt,
                    revision = item.revision,
                )
            } else {
                upsert(item)
            }
        }

    private suspend fun upsert(payload: CollectionSyncPayload) {
        database.collectionDao().upsert(
            CollectionEntity(
                id = payload.id,
                libraryId = payload.libraryId,
                ownerId = payload.ownerId,
                name = payload.name,
                isInbox = payload.isInbox,
                isGlobalAccess = payload.isGlobalAccess,
                revision = payload.revision,
                deletedAt = payload.deletedAt,
                updatedAt = payload.updatedAt,
            ),
        )
    }
}
