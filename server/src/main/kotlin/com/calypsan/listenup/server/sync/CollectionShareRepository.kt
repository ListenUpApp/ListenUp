package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.CollectionShareSyncPayload
import com.calypsan.listenup.server.db.CollectionSharesTable
import kotlin.time.Clock
import kotlinx.serialization.KSerializer
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Syncable repository for collection share records.
 *
 * Handles read/write of [CollectionShareSyncPayload] via [CollectionSharesTable].
 * Permission is stored as a lowercase TEXT column (`"read"` / `"write"`); on read
 * a corrupt or unknown value defaults to [SharePermission.Read] (least-privilege).
 *
 * Service-layer helpers beyond the base substrate:
 *  - [findActiveShare] — fetch the live share for a `(collectionId, userId)` pair
 *  - [listActiveSharesForCollection] — fetch all live shares on a collection
 *  - [listActiveSharesForUser] — fetch all collections shared with a user
 *  - [softDeleteShare] — revoke the active share for a `(collectionId, userId)` pair
 */
class CollectionShareRepository(
    db: Database,
    bus: ChangeBus,
    registry: SyncRegistry,
    clock: Clock = Clock.System,
) : SyncableRepository<CollectionShareSyncPayload, String>(
        db = db,
        table = CollectionSharesTable,
        bus = bus,
        registry = registry,
        domainName = "collection_shares",
        clock = clock,
    ) {
    override val elementSerializer: KSerializer<CollectionShareSyncPayload> = CollectionShareSyncPayload.serializer()

    override val CollectionShareSyncPayload.id: String get() = this.id

    override fun CollectionShareSyncPayload.revisionOf(): Long = revision

    override suspend fun readPayload(idStr: String): CollectionShareSyncPayload? =
        CollectionSharesTable
            .selectAll()
            .where { CollectionSharesTable.id eq idStr }
            .firstOrNull()
            ?.let { row ->
                CollectionShareSyncPayload(
                    id = row[CollectionSharesTable.id],
                    collectionId = row[CollectionSharesTable.collectionId],
                    sharedWithUserId = row[CollectionSharesTable.sharedWithUserId],
                    sharedByUserId = row[CollectionSharesTable.sharedByUserId],
                    permission = row[CollectionSharesTable.permission].toSharePermission(),
                    revision = row[CollectionSharesTable.revision],
                    updatedAt = row[CollectionSharesTable.updatedAt],
                    deletedAt = row[CollectionSharesTable.deletedAt],
                )
            }

    override suspend fun writePayload(
        value: CollectionShareSyncPayload,
        rev: Long,
        now: Long,
        clientOpId: String?,
        userId: String?,
        existed: Boolean,
    ) {
        if (existed) {
            CollectionSharesTable.update({ CollectionSharesTable.id eq value.id }) { stmt ->
                stmt[CollectionSharesTable.collectionId] = value.collectionId
                stmt[CollectionSharesTable.sharedWithUserId] = value.sharedWithUserId
                stmt[CollectionSharesTable.sharedByUserId] = value.sharedByUserId
                stmt[CollectionSharesTable.permission] = value.permission.name.lowercase()
                stmt[CollectionSharesTable.revision] = rev
                stmt[CollectionSharesTable.updatedAt] = now
                stmt[CollectionSharesTable.deletedAt] = null
                stmt[CollectionSharesTable.clientOpId] = clientOpId
            }
        } else {
            CollectionSharesTable.insert { stmt ->
                stmt[CollectionSharesTable.id] = value.id
                stmt[CollectionSharesTable.collectionId] = value.collectionId
                stmt[CollectionSharesTable.sharedWithUserId] = value.sharedWithUserId
                stmt[CollectionSharesTable.sharedByUserId] = value.sharedByUserId
                stmt[CollectionSharesTable.permission] = value.permission.name.lowercase()
                stmt[CollectionSharesTable.revision] = rev
                stmt[CollectionSharesTable.createdAt] = now
                stmt[CollectionSharesTable.updatedAt] = now
                stmt[CollectionSharesTable.deletedAt] = null
                stmt[CollectionSharesTable.clientOpId] = clientOpId
            }
        }
    }

    /**
     * Returns the active (non-tombstoned) share for `(collectionId, sharedWithUserId)`,
     * or null when no live share exists.
     */
    suspend fun findActiveShare(
        collectionId: String,
        userId: String,
    ): CollectionShareSyncPayload? =
        suspendTransaction(db) {
            CollectionSharesTable
                .selectAll()
                .where {
                    (CollectionSharesTable.collectionId eq collectionId) and
                        (CollectionSharesTable.sharedWithUserId eq userId) and
                        CollectionSharesTable.deletedAt.isNull()
                }.firstOrNull()
                ?.let { row ->
                    CollectionShareSyncPayload(
                        id = row[CollectionSharesTable.id],
                        collectionId = row[CollectionSharesTable.collectionId],
                        sharedWithUserId = row[CollectionSharesTable.sharedWithUserId],
                        sharedByUserId = row[CollectionSharesTable.sharedByUserId],
                        permission = row[CollectionSharesTable.permission].toSharePermission(),
                        revision = row[CollectionSharesTable.revision],
                        updatedAt = row[CollectionSharesTable.updatedAt],
                        deletedAt = row[CollectionSharesTable.deletedAt],
                    )
                }
        }

    /**
     * Returns all active (non-tombstoned) shares on [collectionId].
     */
    suspend fun listActiveSharesForCollection(collectionId: String): List<CollectionShareSyncPayload> =
        suspendTransaction(db) {
            CollectionSharesTable
                .selectAll()
                .where {
                    (CollectionSharesTable.collectionId eq collectionId) and
                        CollectionSharesTable.deletedAt.isNull()
                }.map { row ->
                    CollectionShareSyncPayload(
                        id = row[CollectionSharesTable.id],
                        collectionId = row[CollectionSharesTable.collectionId],
                        sharedWithUserId = row[CollectionSharesTable.sharedWithUserId],
                        sharedByUserId = row[CollectionSharesTable.sharedByUserId],
                        permission = row[CollectionSharesTable.permission].toSharePermission(),
                        revision = row[CollectionSharesTable.revision],
                        updatedAt = row[CollectionSharesTable.updatedAt],
                        deletedAt = row[CollectionSharesTable.deletedAt],
                    )
                }
        }

    /**
     * Returns all active (non-tombstoned) shares where [userId] is the recipient.
     */
    suspend fun listActiveSharesForUser(userId: String): List<CollectionShareSyncPayload> =
        suspendTransaction(db) {
            CollectionSharesTable
                .selectAll()
                .where {
                    (CollectionSharesTable.sharedWithUserId eq userId) and
                        CollectionSharesTable.deletedAt.isNull()
                }.map { row ->
                    CollectionShareSyncPayload(
                        id = row[CollectionSharesTable.id],
                        collectionId = row[CollectionSharesTable.collectionId],
                        sharedWithUserId = row[CollectionSharesTable.sharedWithUserId],
                        sharedByUserId = row[CollectionSharesTable.sharedByUserId],
                        permission = row[CollectionSharesTable.permission].toSharePermission(),
                        revision = row[CollectionSharesTable.revision],
                        updatedAt = row[CollectionSharesTable.updatedAt],
                        deletedAt = row[CollectionSharesTable.deletedAt],
                    )
                }
        }

    /**
     * Soft-deletes the active share for `(collectionId, sharedWithUserId)`. Bumps
     * revision and publishes [com.calypsan.listenup.api.sync.SyncEvent.Deleted].
     * Returns [AppResult.Failure] if no live share exists for this pair.
     */
    suspend fun softDeleteShare(
        collectionId: String,
        userId: String,
        clientOpId: String? = null,
    ): AppResult<Unit> {
        val share =
            findActiveShare(collectionId, userId) ?: return AppResult.Failure(
                com.calypsan.listenup.api.error.SyncError.NotFound(
                    domain = domainName,
                    entityId = "$collectionId:$userId",
                ),
            )
        return softDelete(share.id, clientOpId = clientOpId)
    }
}

/**
 * Converts a stored TEXT permission value to [SharePermission].
 *
 * A corrupt or unrecognised value defaults to [SharePermission.Read] — the
 * least-privilege choice — rather than throwing and crashing the read path.
 */
private fun String.toSharePermission(): SharePermission =
    if (this == "write") SharePermission.Write else SharePermission.Read
