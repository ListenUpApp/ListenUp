package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.sync.PlaybackPositionSyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.PlaybackPositionEntity
import com.calypsan.listenup.core.BookId

/**
 * The `playback_positions` domain: one row per book, `lastPlayedAt`-wins on both
 * sides (the `>=` guard also absorbs own-echoes, so echo state is not consulted).
 *
 * SSE `Deleted` is declared [DeleteSemantics.CatchUpOnly]: the event carries only the
 * server's position UUID, which is not a local key (rows key by `bookId`); the
 * tombstone converges via catch-up, which carries the full payload. The domain also
 * opts out of digest reconciliation — the server digests by that same UUID, which the
 * client never stores.
 */
internal fun playbackPositionsDomain(database: ListenUpDatabase): MirroredDomain<PlaybackPositionSyncPayload> =
    MirroredDomain(
        key = SyncDomains.PLAYBACK_POSITIONS,
        syncIdOf = { it.id },
        apply = PlaybackPositionMirrorApply(database),
        conflict =
            ConflictPolicy.NewerWins(
                incomingStamp = { it.lastPlayedAt },
                existingStamp = { payload ->
                    database.playbackPositionDao().get(BookId(payload.bookId))?.lastPlayedAt
                },
            ),
        deletes =
            DeleteSemantics.CatchUpOnly(
                "Deleted carries the server position UUID; local rows key by bookId",
            ),
        digest =
            DigestParticipation.OptOut(
                "server digests by a position UUID the client never stores",
            ),
        writes = WriteTier.Outbox(ops = setOf(OpKind.Upsert)),
    )

/**
 * Room mapping for position payloads. Local-only columns (`hasCustomSpeed`,
 * `syncedAt`, `finishedAt`, `startedAt`) are copied from the existing row so a sync
 * event never nulls client-only data.
 */
internal class PlaybackPositionMirrorApply(
    private val database: ListenUpDatabase,
) : MirrorApply<PlaybackPositionSyncPayload> {
    override suspend fun upsert(payload: PlaybackPositionSyncPayload) {
        val existing = database.playbackPositionDao().get(BookId(payload.bookId))
        database.playbackPositionDao().save(
            PlaybackPositionEntity(
                bookId = BookId(payload.bookId),
                positionMs = payload.positionMs,
                playbackSpeed = payload.playbackSpeed,
                hasCustomSpeed = existing?.hasCustomSpeed ?: false,
                updatedAt = payload.updatedAt,
                syncedAt = existing?.syncedAt,
                lastPlayedAt = payload.lastPlayedAt,
                isFinished = payload.finished,
                finishedAt = existing?.finishedAt,
                startedAt = existing?.startedAt,
                revision = payload.revision,
                deletedAt = payload.deletedAt,
            ),
        )
    }

    override suspend fun tombstoneById(
        id: String,
        deletedAt: Long,
        revision: Long,
    ): Unit = error("unreachable: playback_positions declares DeleteSemantics.CatchUpOnly")

    override suspend fun tombstoneFromItem(item: PlaybackPositionSyncPayload) {
        database.playbackPositionDao().softDelete(
            id = BookId(item.bookId),
            deletedAt = item.deletedAt ?: item.updatedAt,
            revision = item.revision,
        )
    }
}
