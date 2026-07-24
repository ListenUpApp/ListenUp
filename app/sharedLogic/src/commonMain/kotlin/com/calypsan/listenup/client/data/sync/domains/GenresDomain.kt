package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.sync.GenreSyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.client.data.local.db.GenreEntity
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.core.Timestamp

/**
 * The `genres` domain: server-authored hierarchy (admin RPC writes), server-wins
 * apply, soft-delete tombstones, full digest participation, outbox-backed writes.
 *
 * **Outbox writes.** Update and delete write Room optimistically and queue a durable op on
 * [OutboxChannels.Genres] keyed by the genre id; the entity-level in-flight shield defers a
 * genre's own echo until its op drains, so a stale snapshot never reverts the optimistic edit
 * before the authoritative echo lands. Creating a genre (server-minted id/slug), a subtree move
 * (path/depth recompute across descendants), and a merge (server-side relink) all stay online RPCs.
 */
internal fun genresDomain(database: ListenUpDatabase): MirroredDomain<GenreSyncPayload> {
    val apply = GenreMirrorApply(database)
    return MirroredDomain(
        key = SyncDomains.GENRES,
        apply = apply,
        conflict = ConflictPolicy.ServerWins(RevisionGuard { id -> database.genreDao().revisionOf(id) }),
        deletes = DeleteSemantics.SoftDelete(apply::tombstoneById),
        digest = fullDigest(database.genreDao()::digestRows),
        writes = WriteTier.Outbox(OutboxChannels.Genres),
    )
}

/** Room mapping for [GenreSyncPayload] payloads. */
internal class GenreMirrorApply(
    private val database: ListenUpDatabase,
) : MirrorApply<GenreSyncPayload> {
    override suspend fun upsert(payload: GenreSyncPayload) {
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

    suspend fun tombstoneById(
        id: String,
        deletedAt: Long,
        revision: Long,
    ) {
        database.genreDao().softDelete(id = id, deletedAt = deletedAt, revision = revision)
    }

    override suspend fun tombstoneFromItem(item: GenreSyncPayload) {
        tombstoneById(
            id = item.id,
            deletedAt = item.deletedAt ?: item.updatedAt,
            revision = item.revision,
        )
    }
}
