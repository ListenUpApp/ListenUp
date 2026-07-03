package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.TagEntity

/**
 * The `tags` domain: server-authored rows (curators), server-wins apply,
 * soft-delete tombstones, full digest participation, online-only writes.
 * `isOwnEcho` needs no shield: the client has no local tag-row write path
 * that would generate echoes.
 */
internal fun tagsDomain(database: ListenUpDatabase): MirroredDomain<Tag> =
    MirroredDomain(
        key = SyncDomains.TAGS,
        syncIdOf = { it.id },
        apply = TagMirrorApply(database),
        conflict = ConflictPolicy.ServerWins(),
        deletes = DeleteSemantics.SoftDelete,
        digest = fullDigest(database.tagDao()::digestRows),
        writes = WriteTier.OnlineOnly,
    )

/** Room mapping for [Tag] payloads. */
internal class TagMirrorApply(
    private val database: ListenUpDatabase,
) : MirrorApply<Tag> {
    override suspend fun upsert(payload: Tag) {
        database.tagDao().upsert(
            TagEntity(
                id = payload.id,
                name = payload.name,
                slug = payload.slug,
                revision = payload.revision,
                deletedAt = payload.deletedAt,
                updatedAt = payload.updatedAt,
            ),
        )
    }

    override suspend fun tombstoneById(
        id: String,
        deletedAt: Long,
        revision: Long,
    ) {
        database.tagDao().softDelete(id = id, deletedAt = deletedAt, revision = revision)
    }

    override suspend fun tombstoneFromItem(item: Tag) {
        tombstoneById(
            id = item.id,
            deletedAt = item.deletedAt ?: item.updatedAt,
            revision = item.revision,
        )
    }
}
