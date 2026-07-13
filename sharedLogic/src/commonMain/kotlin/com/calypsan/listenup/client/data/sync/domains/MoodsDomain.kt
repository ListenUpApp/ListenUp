package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.sync.Mood
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.MoodEntity

/**
 * The `moods` domain: server-authored rows (curators), server-wins apply,
 * soft-delete tombstones, full digest participation, online-only writes.
 * Structurally identical to [tagsDomain]; An own-echo needs no shield — the
 * client has no local mood-row write path.
 */
internal fun moodsDomain(database: ListenUpDatabase): MirroredDomain<Mood> {
    val apply = MoodMirrorApply(database)
    return MirroredDomain(
        key = SyncDomains.MOODS,
        apply = apply,
        conflict = ConflictPolicy.ServerWins(RevisionGuard { id -> database.moodDao().revisionOf(id) }),
        deletes = DeleteSemantics.SoftDelete(apply::tombstoneById),
        digest = fullDigest(database.moodDao()::digestRows),
        writes = WriteTier.OnlineOnly,
    )
}

/** Room mapping for [Mood] payloads. */
internal class MoodMirrorApply(
    private val database: ListenUpDatabase,
) : MirrorApply<Mood> {
    override suspend fun upsert(payload: Mood) {
        database.moodDao().upsert(
            MoodEntity(
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
        database.moodDao().softDelete(id = id, deletedAt = deletedAt, revision = revision)
    }

    override suspend fun tombstoneFromItem(item: Mood) {
        tombstoneById(
            id = item.id,
            deletedAt = item.deletedAt ?: item.updatedAt,
            revision = item.revision,
        )
    }
}
