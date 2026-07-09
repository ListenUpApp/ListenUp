package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.client.data.local.db.CollectionEntity
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.sync.TargetedFetch

/**
 * The `collections` domain (Collections — Room v24): server-wins apply, soft-delete
 * tombstones, full digest, online-only writes, access-gated.
 *
 * **Access gate:** the server's `pullSince` for collections is filtered to the
 * caller's accessible set (pure-union grant model), so an `AccessChanged` reconcile
 * must prune local rows the user can no longer see. The [AccessGate] tombstones
 * (not hard-deletes) every live row outside the accessible set.
 *
 * `bookCount` is JOIN-derived (never stored), so the apply maps only substrate
 * fields — drift is impossible by construction. `isOwnEcho` needs no shield:
 * `@Upsert` is idempotent.
 */
internal fun collectionsDomain(database: ListenUpDatabase): MirroredDomain<CollectionSyncPayload> {
    val apply = CollectionMirrorApply(database)
    return MirroredDomain(
        key = SyncDomains.COLLECTIONS,
        apply = apply,
        conflict = ConflictPolicy.ServerWins(RevisionGuard { id -> database.collectionDao().revisionOf(id) }),
        deletes = DeleteSemantics.SoftDelete(apply::tombstoneById),
        digest = fullDigest(database.collectionDao()::digestRows),
        writes = WriteTier.OnlineOnly,
        accessGate =
            AccessGate(
                liveIds = database.collectionDao()::liveIds,
                tombstoneByIds = database.collectionDao()::tombstoneByIds,
                // A collection is fetched by its own id; every requested id is a prune candidate.
                delta =
                    AccessDeltaPolicy.Targeted(
                        order = 0,
                        axis = ScopeAxis.Collections,
                        fetchFor = { TargetedFetch.ByIds(it) },
                        candidatesFor = { it.toSet() },
                    ),
            ),
    )
}

/** Room mapping for [CollectionSyncPayload] payloads. */
internal class CollectionMirrorApply(
    private val database: ListenUpDatabase,
) : MirrorApply<CollectionSyncPayload> {
    override suspend fun upsert(payload: CollectionSyncPayload) {
        database.collectionDao().upsert(
            CollectionEntity(
                id = payload.id,
                libraryId = payload.libraryId,
                ownerId = payload.ownerId,
                name = payload.name,
                isInbox = payload.isInbox,
                isSystem = payload.isSystem,
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
        database.collectionDao().softDelete(id = id, deletedAt = deletedAt, revision = revision)
    }

    override suspend fun tombstoneFromItem(item: CollectionSyncPayload) {
        tombstoneById(
            id = item.id,
            deletedAt = item.deletedAt ?: item.updatedAt,
            revision = item.revision,
        )
    }
}
