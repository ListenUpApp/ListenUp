package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.CollectionShareSyncPayload
import com.calypsan.listenup.server.db.CollectionGrantsTable
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
 * Syncable repository for collection grant records.
 *
 * Storage is principal-based ([CollectionGrantsTable]: `principal_type` / `principal_id` /
 * `granted_by_user_id`), but the wire is unchanged: a USER-principal grant maps to a
 * [CollectionShareSyncPayload] (`sharedWithUserId` / `sharedByUserId`) over sync, and the
 * `domainName` stays `"collection_shares"`. [readPayload] / [writePayload] adapt between the
 * two — `principalType` is always `"USER"` on write. The wire/client rename to "grant" is
 * deferred to the phase that introduces GROUP principals.
 *
 * Permission is stored as a lowercase TEXT column (`"read"` / `"write"`); on read a corrupt
 * or unknown value defaults to [SharePermission.Read] (least-privilege).
 *
 * Service-layer helpers beyond the base substrate (all USER-principal scoped):
 *  - [findActiveGrant] — fetch the live grant for a `(collectionId, userId)` pair
 *  - [listActiveGrantsForCollection] — fetch all live USER grants on a collection
 *  - [listActiveGrantsForUser] — fetch all collections granted to a user
 *  - [softDeleteGrant] — revoke the active grant for a `(collectionId, userId)` pair
 */
class CollectionGrantRepository(
    db: Database,
    bus: ChangeBus,
    registry: SyncRegistry,
    clock: Clock = Clock.System,
) : SyncableRepository<CollectionShareSyncPayload, String>(
        db = db,
        table = CollectionGrantsTable,
        bus = bus,
        registry = registry,
        // Wire domain stays "collection_shares" — see class KDoc. A USER-principal grant is a
        // share on the wire; the wire/client rename is deferred to when GROUP principals exist.
        domainName = "collection_shares",
        clock = clock,
    ) {
    override val elementSerializer: KSerializer<CollectionShareSyncPayload> = CollectionShareSyncPayload.serializer()

    override val CollectionShareSyncPayload.id: String get() = this.id

    override fun CollectionShareSyncPayload.revisionOf(): Long = revision

    override suspend fun readPayload(idStr: String): CollectionShareSyncPayload? =
        CollectionGrantsTable
            .selectAll()
            .where { CollectionGrantsTable.id eq idStr }
            .firstOrNull()
            ?.toSharePayload()

    override suspend fun writePayload(
        value: CollectionShareSyncPayload,
        rev: Long,
        now: Long,
        clientOpId: String?,
        userId: String?,
        existed: Boolean,
    ) {
        // A USER-principal grant is the only kind today; map the share payload onto the
        // principal columns (principalType = "USER").
        if (existed) {
            CollectionGrantsTable.update({ CollectionGrantsTable.id eq value.id }) { stmt ->
                stmt[CollectionGrantsTable.collectionId] = value.collectionId
                stmt[CollectionGrantsTable.principalType] = "USER"
                stmt[CollectionGrantsTable.principalId] = value.sharedWithUserId
                stmt[CollectionGrantsTable.grantedByUserId] = value.sharedByUserId
                stmt[CollectionGrantsTable.permission] = value.permission.name.lowercase()
                stmt[CollectionGrantsTable.revision] = rev
                stmt[CollectionGrantsTable.updatedAt] = now
                stmt[CollectionGrantsTable.deletedAt] = null
                stmt[CollectionGrantsTable.clientOpId] = clientOpId
            }
        } else {
            CollectionGrantsTable.insert { stmt ->
                stmt[CollectionGrantsTable.id] = value.id
                stmt[CollectionGrantsTable.collectionId] = value.collectionId
                stmt[CollectionGrantsTable.principalType] = "USER"
                stmt[CollectionGrantsTable.principalId] = value.sharedWithUserId
                stmt[CollectionGrantsTable.grantedByUserId] = value.sharedByUserId
                stmt[CollectionGrantsTable.permission] = value.permission.name.lowercase()
                stmt[CollectionGrantsTable.revision] = rev
                stmt[CollectionGrantsTable.createdAt] = now
                stmt[CollectionGrantsTable.updatedAt] = now
                stmt[CollectionGrantsTable.deletedAt] = null
                stmt[CollectionGrantsTable.clientOpId] = clientOpId
            }
        }
    }

    /**
     * Returns the active (non-tombstoned) USER grant for `(collectionId, userId)`,
     * or null when no live grant exists.
     */
    suspend fun findActiveGrant(
        collectionId: String,
        userId: String,
    ): CollectionShareSyncPayload? =
        suspendTransaction(db) {
            CollectionGrantsTable
                .selectAll()
                .where {
                    (CollectionGrantsTable.collectionId eq collectionId) and
                        (CollectionGrantsTable.principalType eq "USER") and
                        (CollectionGrantsTable.principalId eq userId) and
                        CollectionGrantsTable.deletedAt.isNull()
                }.firstOrNull()
                ?.toSharePayload()
        }

    /**
     * Returns all active (non-tombstoned) USER grants on [collectionId].
     */
    suspend fun listActiveGrantsForCollection(collectionId: String): List<CollectionShareSyncPayload> =
        suspendTransaction(db) {
            CollectionGrantsTable
                .selectAll()
                .where {
                    (CollectionGrantsTable.collectionId eq collectionId) and
                        (CollectionGrantsTable.principalType eq "USER") and
                        CollectionGrantsTable.deletedAt.isNull()
                }.map { it.toSharePayload() }
        }

    /**
     * Returns all active (non-tombstoned) USER grants where [userId] is the recipient.
     */
    suspend fun listActiveGrantsForUser(userId: String): List<CollectionShareSyncPayload> =
        suspendTransaction(db) {
            CollectionGrantsTable
                .selectAll()
                .where {
                    (CollectionGrantsTable.principalType eq "USER") and
                        (CollectionGrantsTable.principalId eq userId) and
                        CollectionGrantsTable.deletedAt.isNull()
                }.map { it.toSharePayload() }
        }

    /**
     * Soft-deletes the active USER grant for `(collectionId, userId)`. Bumps revision and
     * publishes [com.calypsan.listenup.api.sync.SyncEvent.Deleted]. Returns
     * [AppResult.Failure] if no live grant exists for this pair.
     */
    suspend fun softDeleteGrant(
        collectionId: String,
        userId: String,
        clientOpId: String? = null,
    ): AppResult<Unit> {
        val grant =
            findActiveGrant(collectionId, userId) ?: return AppResult.Failure(
                com.calypsan.listenup.api.error.SyncError.NotFound(
                    domain = domainName,
                    entityId = "$collectionId:$userId",
                ),
            )
        return softDelete(grant.id, clientOpId = clientOpId)
    }
}

/**
 * Adapts a [CollectionGrantsTable] row to the unchanged wire [CollectionShareSyncPayload]:
 * `principalId` → `sharedWithUserId`, `grantedByUserId` → `sharedByUserId`.
 */
private fun org.jetbrains.exposed.v1.core.ResultRow.toSharePayload(): CollectionShareSyncPayload =
    CollectionShareSyncPayload(
        id = this[CollectionGrantsTable.id],
        collectionId = this[CollectionGrantsTable.collectionId],
        sharedWithUserId = this[CollectionGrantsTable.principalId],
        sharedByUserId = this[CollectionGrantsTable.grantedByUserId],
        permission = this[CollectionGrantsTable.permission].toSharePermission(),
        revision = this[CollectionGrantsTable.revision],
        updatedAt = this[CollectionGrantsTable.updatedAt],
        deletedAt = this[CollectionGrantsTable.deletedAt],
    )

/**
 * Converts a stored TEXT permission value to [SharePermission].
 *
 * A corrupt or unrecognised value defaults to [SharePermission.Read] — the
 * least-privilege choice — rather than throwing and crashing the read path.
 */
private fun String.toSharePermission(): SharePermission =
    if (this == "write") SharePermission.Write else SharePermission.Read
