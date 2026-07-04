package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.sync.ActivitySyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.client.data.local.db.ActivityEntity
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase

/**
 * The `activities` domain: the social activity feed as a cursored mirror. Server-authored and
 * append-only — a row is written exactly once and the existing row is authoritative
 * ([ConflictPolicy.AppendOnly], insert-if-absent in the apply).
 *
 * SSE `Deleted` is [DeleteSemantics.CatchUpOnly]: the server does not emit activity tombstones over
 * the live tail (the feed is append-only); a defensive frame converges via catch-up, which carries
 * the full payload and soft-deletes by id. Full digest participation. [WriteTier.ServerOwned] —
 * clients never author activities.
 *
 * Unlike `listening_events`, the payload CARRIES its `userId` (activities are a cross-user feed, not
 * a per-user stream), so no signed-in-user stamping is needed — the row is written verbatim, and
 * identity/book display are enriched at read time by joining the local mirrors.
 */
internal fun activitiesDomain(database: ListenUpDatabase): MirroredDomain<ActivitySyncPayload> =
    MirroredDomain(
        key = SyncDomains.ACTIVITIES,
        syncIdOf = { it.id },
        apply = ActivityMirrorApply(database),
        conflict = ConflictPolicy.AppendOnly(),
        deletes =
            DeleteSemantics.CatchUpOnly(
                "server never emits activity tombstones over SSE; catch-up converges them",
            ),
        digest = fullDigest(database.activityDao()::digestRows),
        writes = WriteTier.ServerOwned,
    )

/**
 * Room mapping for [ActivitySyncPayload]: insert-if-absent (append-only guard); the raw row is
 * written verbatim (no denormalized display — enrichment is read-time). See [activitiesDomain].
 */
internal class ActivityMirrorApply(
    private val database: ListenUpDatabase,
) : MirrorApply<ActivitySyncPayload> {
    override suspend fun upsert(payload: ActivitySyncPayload) {
        if (database.activityDao().getById(payload.id) != null) return
        database.activityDao().upsert(
            ActivityEntity(
                id = payload.id,
                userId = payload.userId,
                type = payload.type,
                occurredAt = payload.occurredAt,
                bookId = payload.bookId,
                isReread = payload.isReread,
                durationMs = payload.durationMs,
                milestoneValue = payload.milestoneValue,
                milestoneUnit = payload.milestoneUnit,
                shelfId = payload.shelfId,
                shelfName = payload.shelfName,
                revision = payload.revision,
                deletedAt = payload.deletedAt,
            ),
        )
    }

    override suspend fun tombstoneById(
        id: String,
        deletedAt: Long,
        revision: Long,
    ): Unit = error("unreachable: activities declares DeleteSemantics.CatchUpOnly")

    override suspend fun tombstoneFromItem(item: ActivitySyncPayload) {
        database.activityDao().softDelete(
            id = item.id,
            deletedAt = item.deletedAt ?: item.updatedAt,
            revision = item.revision,
        )
    }
}
