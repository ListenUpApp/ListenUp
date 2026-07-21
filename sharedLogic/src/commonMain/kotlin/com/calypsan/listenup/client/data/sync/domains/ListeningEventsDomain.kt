package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.sync.ListeningEventSyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.ListeningEventEntity
import com.calypsan.listenup.client.domain.repository.AuthSession
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * The `listening_events` domain: append-only closed listening spans — a row is
 * written exactly once and the existing row is authoritative (the server never
 * mutates a committed span). Insert-if-absent lives in the apply per
 * [ConflictPolicy.AppendOnly]'s contract.
 *
 * Firehose `Deleted` is declared [DeleteSemantics.CatchUpOnly]: the server does not emit
 * listening-event tombstones; a defensive frame converges via catch-up, which
 * carries the full payload and soft-deletes by id. Full digest participation;
 * outbox upserts (the recorder enqueues `"upsert"` ops with client-generated ids).
 *
 * **Ownership stamping.** The wire payload omits `userId` — the server only streams
 * a client its own user's events. Rows are stamped via [AuthSession.getUserId]
 * (persisted storage; does not race `authState`) so they share the id of
 * locally-recorded events and stay visible to the user-scoped stats query. A null
 * id (signed out) skips the item; a later catch-up re-applies it.
 */
internal fun listeningEventsDomain(
    database: ListenUpDatabase,
    authSession: AuthSession,
): MirroredDomain<ListeningEventSyncPayload> =
    MirroredDomain(
        key = SyncDomains.LISTENING_EVENTS,
        apply = ListeningEventMirrorApply(database, authSession),
        conflict = ConflictPolicy.AppendOnly(),
        deletes =
            DeleteSemantics.CatchUpOnly(
                "server never emits listening-event tombstones on the firehose; catch-up converges them",
            ),
        digest = fullDigest(database.listeningEventDao()::digestRows),
        writes = WriteTier.Outbox(OutboxChannels.ListeningEvents),
    )

/**
 * Room mapping for [ListeningEventSyncPayload]: insert-if-absent (append-only guard)
 * with signed-in-user stamping. The un-tombstone / revision-converge healing is the
 * substrate policy in [AppendOnlyMirrorApply]; only the [insert] stamping is domain
 * logic. See [listeningEventsDomain] for why the stamping is domain-specific.
 */
internal class ListeningEventMirrorApply(
    private val database: ListenUpDatabase,
    private val authSession: AuthSession,
) : AppendOnlyMirrorApply<ListeningEventSyncPayload>() {
    override suspend fun insert(payload: ListeningEventSyncPayload) {
        // Resolve the signed-in id from persisted storage (race-free) — authState may still be
        // Initializing during the startup catch-up, which previously poisoned rows with "".
        val currentUserId = authSession.getUserId()
        if (currentUserId == null) {
            logger.warn {
                "[listening_events] no signed-in user while applying ${payload.id} — " +
                    "skipping; a later catch-up re-applies"
            }
            return
        }

        database.listeningEventDao().upsert(
            ListeningEventEntity(
                id = payload.id,
                userId = currentUserId,
                bookId = payload.bookId,
                startPositionMs = payload.startPositionMs,
                endPositionMs = payload.endPositionMs,
                startedAt = payload.startedAt,
                endedAt = payload.endedAt,
                playbackSpeed = payload.playbackSpeed,
                tz = payload.tz,
                deviceLabel = payload.deviceLabel,
                revision = payload.revision,
                deletedAt = payload.deletedAt,
            ),
        )
    }

    override suspend fun readMeta(id: String): AppendOnlyRowMeta? =
        database.listeningEventDao().getById(id)?.let { AppendOnlyRowMeta(it.deletedAt, it.revision) }

    override suspend fun restore(
        id: String,
        revision: Long,
    ) = database.listeningEventDao().restore(id, revision)

    override suspend fun updateRevision(
        id: String,
        revision: Long,
    ) = database.listeningEventDao().updateRevision(id, revision)

    override suspend fun tombstoneFromItem(item: ListeningEventSyncPayload) {
        database.listeningEventDao().softDelete(
            id = item.id,
            deletedAt = item.deletedAt ?: item.updatedAt,
            revision = item.revision,
        )
    }
}
