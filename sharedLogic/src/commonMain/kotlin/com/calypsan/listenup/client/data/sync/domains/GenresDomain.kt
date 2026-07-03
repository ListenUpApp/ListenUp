package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.sync.GenreSyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.client.data.local.db.GenreEntity
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.core.Timestamp

/**
 * The `genres` domain: server-authored hierarchy (admin RPC writes), server-wins
 * apply, soft-delete tombstones, full digest participation, online-only writes.
 * `isOwnEcho` needs no shield: the client has no local genre-row write path.
 */
internal fun genresDomain(database: ListenUpDatabase): MirroredDomain<GenreSyncPayload> =
    MirroredDomain(
        key = SyncDomains.GENRES,
        syncIdOf = { it.id },
        apply = GenreMirrorApply(database),
        conflict = ConflictPolicy.ServerWins(),
        deletes = DeleteSemantics.SoftDelete,
        digest = fullDigest(database.genreDao()::digestRows),
        writes = WriteTier.OnlineOnly,
    )

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

    override suspend fun tombstoneById(
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
