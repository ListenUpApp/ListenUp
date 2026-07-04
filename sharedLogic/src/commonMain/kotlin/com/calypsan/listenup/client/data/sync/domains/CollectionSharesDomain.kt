package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.sync.CollectionShareSyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.client.data.local.db.CollectionShareEntity
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase

/**
 * The `collection_shares` domain (Collections — Room v24): share-grant rows keyed by
 * their own row UUID (entity-shaped, not a composite junction). Server-wins apply,
 * soft-delete tombstones (a revoked share), full digest, online-only writes,
 * access-gated.
 *
 * **Wire-name skew:** the wire name is `collection_shares`; server storage is the
 * `collection_grants` table. The freeze is documented on
 * [com.calypsan.listenup.api.sync.SyncDomains.COLLECTION_SHARES] — client cursors
 * are keyed by the wire name, so it can never change.
 *
 * The wire [SharePermission] enum is persisted as its stable lowercase string
 * (`"read"` / `"write"`) via [permissionWireValue], matching the column contract on
 * [CollectionShareEntity.permission]. `isOwnEcho` needs no shield: `@Upsert` is
 * idempotent.
 */
internal fun collectionSharesDomain(database: ListenUpDatabase): MirroredDomain<CollectionShareSyncPayload> {
    val apply = CollectionShareMirrorApply(database)
    return MirroredDomain(
        key = SyncDomains.COLLECTION_SHARES,
        apply = apply,
        conflict = ConflictPolicy.ServerWins(RevisionGuard { id -> database.collectionShareDao().revisionOf(id) }),
        deletes = DeleteSemantics.SoftDelete(apply::tombstoneById),
        digest = fullDigest(database.collectionShareDao()::digestRows),
        writes = WriteTier.OnlineOnly,
        accessGate =
            AccessGate(
                localLiveIds = { database.collectionShareDao().liveIds().toSet() },
                pruneTo = { accessibleIds, now ->
                    database.collectionShareDao().tombstoneNotIn(accessibleIds, now)
                },
            ),
    )
}

/** Room mapping for [CollectionShareSyncPayload] payloads. */
internal class CollectionShareMirrorApply(
    private val database: ListenUpDatabase,
) : MirrorApply<CollectionShareSyncPayload> {
    override suspend fun upsert(payload: CollectionShareSyncPayload) {
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

    suspend fun tombstoneById(
        id: String,
        deletedAt: Long,
        revision: Long,
    ) {
        database.collectionShareDao().softDelete(id = id, deletedAt = deletedAt, revision = revision)
    }

    override suspend fun tombstoneFromItem(item: CollectionShareSyncPayload) {
        tombstoneById(
            id = item.id,
            deletedAt = item.deletedAt ?: item.updatedAt,
            revision = item.revision,
        )
    }
}

/** The stable lowercase wire string for this permission (`"read"` / `"write"`). */
private fun SharePermission.permissionWireValue(): String =
    when (this) {
        SharePermission.Read -> "read"
        SharePermission.Write -> "write"
    }
