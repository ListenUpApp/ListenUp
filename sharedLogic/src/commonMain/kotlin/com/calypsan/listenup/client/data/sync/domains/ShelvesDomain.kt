package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.sync.ShelfSyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.ShelfEntity

/**
 * The `shelves` domain: user-scoped own-data — the server already scopes pull and
 * firehose queries to the caller's rows, so no [AccessGate] is declared (the genres
 * precedent). Server-wins apply (idempotent upsert absorbs own echoes), soft-delete
 * tombstones, full digest, online-only writes. `bookCount` is JOIN-derived and never
 * stored, so drift is impossible by construction.
 */
internal fun shelvesDomain(database: ListenUpDatabase): MirroredDomain<ShelfSyncPayload> {
    val apply = ShelfMirrorApply(database)
    return MirroredDomain(
        key = SyncDomains.SHELVES,
        apply = apply,
        conflict = ConflictPolicy.ServerWins(RevisionGuard { id -> database.shelfDao().revisionOf(id) }),
        deletes = DeleteSemantics.SoftDelete(apply::tombstoneById),
        digest = fullDigest(database.shelfDao()::digestRows),
        writes = WriteTier.OnlineOnly,
    )
}

/** Room mapping for [ShelfSyncPayload] payloads. */
internal class ShelfMirrorApply(
    private val database: ListenUpDatabase,
) : MirrorApply<ShelfSyncPayload> {
    override suspend fun upsert(payload: ShelfSyncPayload) {
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

    suspend fun tombstoneById(
        id: String,
        deletedAt: Long,
        revision: Long,
    ) {
        database.shelfDao().softDelete(id = id, deletedAt = deletedAt, revision = revision)
    }

    override suspend fun tombstoneFromItem(item: ShelfSyncPayload) {
        tombstoneById(
            id = item.id,
            deletedAt = item.deletedAt ?: item.updatedAt,
            revision = item.revision,
        )
    }
}
