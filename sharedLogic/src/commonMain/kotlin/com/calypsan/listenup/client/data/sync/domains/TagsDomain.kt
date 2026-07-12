package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.TagEntity

/**
 * The `tags` domain: server-authored rows (curators), server-wins apply,
 * soft-delete tombstones, full digest participation, online-only writes.
 * An own-echo needs no shield: the client has no local tag-row write path
 * that would generate echoes.
 */
internal fun tagsDomain(database: ListenUpDatabase): MirroredDomain<Tag> {
    val apply = TagMirrorApply(database)
    return MirroredDomain(
        key = SyncDomains.TAGS,
        apply = apply,
        conflict = ConflictPolicy.ServerWins(RevisionGuard { id -> database.tagDao().revisionOf(id) }),
        deletes = DeleteSemantics.SoftDelete(apply::tombstoneById),
        digest = fullDigest(database.tagDao()::digestRows),
        writes = WriteTier.OnlineOnly,
    )
}

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

    suspend fun tombstoneById(
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
