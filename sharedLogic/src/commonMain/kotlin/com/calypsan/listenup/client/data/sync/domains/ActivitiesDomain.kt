package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.sync.ActivitySyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.client.data.local.db.ActivityEntity
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.sync.TargetedFetch

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
 *
 * **Access-gated** (like `books`): the server filters catch-up/digest to the caller's accessible set
 * (a row is visible iff its book is accessible or it has no book). The [AccessGate] makes the composed
 * handler an `AccessFilteredSyncHandler`, so a book deletion / share revocation — which drops the
 * activity out of the member's accessible set — prunes it locally via `AccessChanged`.
 *
 * **Delta participation:** the domain is fetched on the `books` axis by the changed books' ids
 * ([TargetedFetch.ByBookIds]) — a GRANT re-delivers the now-accessible activity live in the same
 * delta pass, and a REVOKE tombstones the requested-but-not-returned rows via `pruneWithin`. The
 * candidate set is exactly the local live activities gating on the scoped books, so an activity on a
 * book outside the scope is never a prune candidate. The coarse anchor and digest backstop cover any
 * dropped frame.
 */
internal fun activitiesDomain(database: ListenUpDatabase): MirroredDomain<ActivitySyncPayload> =
    MirroredDomain(
        key = SyncDomains.ACTIVITIES,
        apply = ActivityMirrorApply(database),
        conflict = ConflictPolicy.AppendOnly(),
        deletes =
            DeleteSemantics.CatchUpOnly(
                "server never emits activity tombstones over SSE; catch-up converges them",
            ),
        digest = fullDigest(database.activityDao()::digestRows),
        writes = WriteTier.ServerOwned,
        accessGate =
            AccessGate(
                liveIds = database.activityDao()::liveIds,
                tombstoneByIds = database.activityDao()::tombstoneByIds,
                delta =
                    AccessDeltaPolicy.Targeted(
                        order = 3,
                        axis = ScopeAxis.Books,
                        fetchFor = { TargetedFetch.ByBookIds(it) },
                        // The local live activities gating on the scoped books — chunked under the
                        // bind-variable ceiling because a scope can name more books than SQLite holds.
                        candidatesFor = { bookIds ->
                            bookIds
                                .chunked(SQLITE_IN_CHUNK)
                                .flatMapTo(mutableSetOf()) { database.activityDao().liveIdsForBooks(it) }
                        },
                    ),
            ),
    )

/**
 * Room mapping for [ActivitySyncPayload]: insert-if-absent (append-only guard); the raw row is
 * written verbatim (no denormalized display — enrichment is read-time). The un-tombstone /
 * revision-converge healing is the substrate policy in [AppendOnlyMirrorApply]. See
 * [activitiesDomain].
 */
internal class ActivityMirrorApply(
    private val database: ListenUpDatabase,
) : AppendOnlyMirrorApply<ActivitySyncPayload>() {
    override suspend fun insert(payload: ActivitySyncPayload) {
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

    override suspend fun readMeta(id: String): AppendOnlyRowMeta? =
        database.activityDao().getById(id)?.let { AppendOnlyRowMeta(it.deletedAt, it.revision) }

    override suspend fun restore(
        id: String,
        revision: Long,
    ) = database.activityDao().restore(id, revision)

    override suspend fun updateRevision(
        id: String,
        revision: Long,
    ) = database.activityDao().updateRevision(id, revision)

    override suspend fun tombstoneFromItem(item: ActivitySyncPayload) {
        database.activityDao().softDelete(
            id = item.id,
            deletedAt = item.deletedAt ?: item.updatedAt,
            revision = item.revision,
        )
    }
}
