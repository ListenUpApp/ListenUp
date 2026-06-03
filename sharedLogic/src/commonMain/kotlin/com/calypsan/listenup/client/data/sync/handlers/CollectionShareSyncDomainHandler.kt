package com.calypsan.listenup.client.data.sync.handlers

import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.sync.CollectionShareSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.client.data.local.db.CollectionShareEntity
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.sync.AccessFilteredSyncHandler
import com.calypsan.listenup.client.data.sync.ClientSyncDomainRegistry
import com.calypsan.listenup.client.data.sync.SyncDomainHandler
import com.calypsan.listenup.core.AppResult
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Client-side sync handler for the `collection_shares` domain (Collections — Room v24).
 *
 * Applies server sync events into the Room `collection_shares` table. Share rows carry the
 * full wire payload on [SyncEvent.Created] and [SyncEvent.Updated]; [SyncEvent.Deleted] events
 * soft-delete the row (a revoked share) via
 * [com.calypsan.listenup.client.data.local.db.CollectionShareDao.softDelete].
 *
 * The wire [SharePermission] enum is persisted as its stable lowercase string (`"read"` /
 * `"write"`) — see [permissionWireValue] — matching the column contract documented on
 * [CollectionShareEntity.permission].
 *
 * `isOwnEcho` is passed through but not acted on; `@Upsert` is idempotent.
 *
 * Self-registers in [ClientSyncDomainRegistry] at construction.
 */
class CollectionShareSyncDomainHandler(
    private val database: ListenUpDatabase,
    private val transactionRunner: TransactionRunner,
    registry: ClientSyncDomainRegistry,
) : SyncDomainHandler<CollectionShareSyncPayload>,
    AccessFilteredSyncHandler {
    override val domainName: String = "collection_shares"
    override val payloadSerializer = CollectionShareSyncPayload.serializer()

    override fun syncId(item: CollectionShareSyncPayload): String = item.id

    init {
        registry.register(this)
    }

    override suspend fun localLiveIds(): Set<String> = database.collectionShareDao().liveIds().toSet()

    override suspend fun pruneTo(
        accessibleIds: Set<String>,
        now: Long,
    ) = database.collectionShareDao().tombstoneNotIn(accessibleIds, now)

    override suspend fun localDigestRows(maxRevision: Long): List<Pair<String, Long>> =
        database.collectionShareDao().digestRows(maxRevision).map { it.id to it.revision }

    override suspend fun onEvent(
        event: SyncEvent<CollectionShareSyncPayload>,
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
                    database.collectionShareDao().softDelete(
                        id = event.id,
                        deletedAt = event.occurredAt,
                        revision = event.revision,
                    )
                }
            }
        }

    override suspend fun onCatchUpItem(
        item: CollectionShareSyncPayload,
        isTombstone: Boolean,
    ): AppResult<Unit> =
        transactionRunner.applyEventAtomically(domainName, item.id, logger) {
            if (isTombstone) {
                database.collectionShareDao().softDelete(
                    id = item.id,
                    deletedAt = item.deletedAt ?: item.updatedAt,
                    revision = item.revision,
                )
            } else {
                upsert(item)
            }
        }

    private suspend fun upsert(payload: CollectionShareSyncPayload) {
        database.collectionShareDao().upsert(
            CollectionShareEntity(
                id = payload.id,
                collectionId = payload.collectionId,
                sharedWithUserId = payload.sharedWithUserId,
                sharedByUserId = payload.sharedByUserId,
                permission = payload.permission.permissionWireValue(),
                revision = payload.revision,
                deletedAt = payload.deletedAt,
                updatedAt = payload.updatedAt,
            ),
        )
    }
}

/** The stable lowercase wire string for this permission (`"read"` / `"write"`). */
private fun SharePermission.permissionWireValue(): String =
    when (this) {
        SharePermission.Read -> "read"
        SharePermission.Write -> "write"
    }
