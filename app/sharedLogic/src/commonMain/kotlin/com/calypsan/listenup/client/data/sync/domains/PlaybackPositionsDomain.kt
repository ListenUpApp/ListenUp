package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.sync.PlaybackPositionSyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.PlaybackPositionEntity
import com.calypsan.listenup.core.BookId

/**
 * The `playback_positions` domain: one row per book, `lastPlayedAt`-wins on both
 * sides (the `>=` guard also absorbs own-echoes, so echo state is not consulted).
 * `lastPlayedAt` is the conflict key because it is the wall-clock of the actual
 * listening moment — not `updatedAt`, which merely records the write. Skipping a
 * stale inbound snapshot is safe, not lossy: the local row is at least as fresh and
 * pushes its own value to the server when connectivity allows.
 *
 * Firehose `Deleted` is declared [DeleteSemantics.CatchUpOnly]: the event carries only the
 * server's position UUID, which is not a local key (rows key by `bookId`); the
 * tombstone converges via catch-up, which carries the full payload. The interim gap
 * is harmless — position deletes accompany book deletion, and the book's own
 * `Deleted` event (books domain) removes the book from view immediately. The domain
 * also opts out of digest reconciliation — the server digests by that same UUID,
 * which the client never stores — so drift is not permanent either: positions
 * self-heal via `lastPlayedAt`-wins re-saves on the next playback.
 */
internal fun playbackPositionsDomain(database: ListenUpDatabase): MirroredDomain<PlaybackPositionSyncPayload> =
    MirroredDomain(
        key = SyncDomains.PLAYBACK_POSITIONS,
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
        writes = WriteTier.Outbox(OutboxChannels.Positions),
    )

/**
 * Room mapping for position payloads.
 *
 * `syncedAt` and `startedAt` stay genuinely client-local — they are copied from the existing row,
 * so a sync event never nulls them.
 *
 * `hasCustomSpeed` and `hasCustomBoost` are authoritative from the payload: they are synced
 * fields, and a listener who resets a book to the account default sends `false` deliberately.
 * Preserving the local value would make that reset unable to cross devices.
 *
 * `finishedAt` and `measuredGainDb` are *null-preserving* rather than verbatim: a null means
 * "this device doesn't know", not "erase it", so an echo from a device that never finished the
 * book — or never measured its loudness — cannot wipe what another device recorded. This mirrors
 * the server's own COALESCE on both columns (`PlaybackPositions.sq` `update`).
 */
internal class PlaybackPositionMirrorApply(
    private val database: ListenUpDatabase,
) : MirrorApply<PlaybackPositionSyncPayload> {
    override suspend fun upsert(payload: PlaybackPositionSyncPayload) {
        // Second read of this row in the transaction (NewerWins.existingStamp read it first) —
        // deliberate: the policy seam cannot hand its read to the apply seam, and both run
        // inside the composed handler's one IMMEDIATE transaction, so the reads cannot diverge.
        val existing = database.playbackPositionDao().get(BookId(payload.bookId))
        database.playbackPositionDao().save(
            PlaybackPositionEntity(
                bookId = BookId(payload.bookId),
                positionMs = payload.positionMs,
                playbackSpeed = payload.playbackSpeed,
                hasCustomSpeed = payload.hasCustomSpeed,
                volumeBoostDb = payload.volumeBoostDb,
                hasCustomBoost = payload.hasCustomBoost,
                // A null in the payload means "this device never measured", not "erase the
                // measurement". Mirrors the server's own COALESCE on `measured_gain_db`
                // (PlaybackPositions.sq `update`).
                measuredGainDb = payload.measuredGainDb ?: existing?.measuredGainDb,
                updatedAt = payload.updatedAt,
                syncedAt = existing?.syncedAt,
                lastPlayedAt = payload.lastPlayedAt,
                isFinished = payload.finished,
                finishedAt = payload.finishedAt ?: existing?.finishedAt,
                startedAt = existing?.startedAt,
                revision = payload.revision,
                deletedAt = payload.deletedAt,
            ),
        )
    }

    override suspend fun tombstoneFromItem(item: PlaybackPositionSyncPayload) {
        database.playbackPositionDao().softDelete(
            id = BookId(item.bookId),
            deletedAt = item.deletedAt ?: item.updatedAt,
            revision = item.revision,
        )
    }
}
