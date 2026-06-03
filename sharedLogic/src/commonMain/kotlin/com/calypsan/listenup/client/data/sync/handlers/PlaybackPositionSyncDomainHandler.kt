package com.calypsan.listenup.client.data.sync.handlers

import com.calypsan.listenup.api.sync.PlaybackPositionSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.PlaybackPositionEntity
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.sync.ClientSyncDomainRegistry
import com.calypsan.listenup.client.data.sync.SyncDomainHandler
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Client-side sync handler for the `playback_positions` domain (Playback-P1).
 *
 * Applies server sync events into the Room `playback_positions` table. One row
 * per book is the client's resume point; the server is the shared authority for
 * cross-device convergence.
 *
 * **`lastPlayedAt`-wins conflict policy.** The server uses `lastPlayedAt` (the
 * wall-clock of the actual listening moment) as the conflict key — the write with
 * the greatest `lastPlayedAt` wins on the server. The client mirrors this on the
 * apply side: if the local row's `lastPlayedAt` is greater than or equal to the
 * incoming event's `lastPlayedAt`, the event is a no-op. The local row is at least
 * as fresh; it will push its own value to the server when connectivity allows. A
 * strict `>=` comparison also absorbs own-echo events: an echo's `lastPlayedAt`
 * equals the local row's (the client wrote it), so the `>=` guard catches it and
 * prevents a UI flicker. `isOwnEcho` is therefore not consulted for the
 * apply-vs-reject decision — the timestamp comparison is sufficient.
 *
 * **Local-only column preservation.** The wire payload carries sync-relevant fields
 * (`positionMs`, `lastPlayedAt`, `finished`, `playbackSpeed`, `currentChapterId`,
 * `revision`, `deletedAt`, `updatedAt`). Local-only fields (`hasCustomSpeed`,
 * `syncedAt`, `finishedAt`, `startedAt`) are client-only concerns and are never
 * overwritten by a sync event.
 *
 * **`SyncEvent.Deleted` handling.** A position tombstone arrives via `SyncEvent.Deleted`
 * when the server deletes the position record (e.g., when its book is deleted). Because
 * the entity is keyed by `bookId` and the `Deleted` event carries only the position's
 * server UUID (`event.id`), a direct soft-delete by server UUID is not possible without
 * an additional lookup column. The tombstone reaches the client via `onCatchUpItem`
 * (which carries the full payload including `bookId`); `SyncEvent.Deleted` is therefore
 * treated as a no-op at the SSE level. This is safe: the book's own `Deleted` SSE event
 * (handled by `BookSyncDomainHandler`) removes the book, and `onCatchUpItem` converges
 * the position tombstone on the next catch-up pass.
 *
 * Self-registers in [ClientSyncDomainRegistry] at construction.
 */
class PlaybackPositionSyncDomainHandler(
    private val database: ListenUpDatabase,
    private val transactionRunner: TransactionRunner,
    registry: ClientSyncDomainRegistry,
) : SyncDomainHandler<PlaybackPositionSyncPayload> {
    override val domainName: String = "playback_positions"
    override val payloadSerializer = PlaybackPositionSyncPayload.serializer()

    override fun syncId(item: PlaybackPositionSyncPayload): String = item.id

    init {
        registry.register(this)
    }

    override suspend fun onEvent(
        event: SyncEvent<PlaybackPositionSyncPayload>,
        isOwnEcho: Boolean,
    ): AppResult<Unit> =
        transactionRunner.applyEventAtomically(domainName, event.id, logger) {
            when (event) {
                is SyncEvent.Created -> {
                    upsert(event.payload)
                }

                is SyncEvent.Updated -> {
                    upsert(event.payload)
                }

                is SyncEvent.Deleted -> {
                    // Cannot soft-delete by server position UUID — entity is keyed by bookId.
                    // The tombstone converges via onCatchUpItem (which carries the full payload).
                    // See class KDoc for a full explanation.
                    logger.debug { "[$domainName] Deleted event for ${event.id} — deferred to catch-up" }
                }
            }
        }

    override suspend fun onCatchUpItem(
        item: PlaybackPositionSyncPayload,
        isTombstone: Boolean,
    ): AppResult<Unit> =
        transactionRunner.applyEventAtomically(domainName, item.id, logger) {
            if (isTombstone) {
                database.playbackPositionDao().softDelete(
                    id = BookId(item.bookId),
                    deletedAt = item.deletedAt ?: item.updatedAt,
                    revision = item.revision,
                )
            } else {
                upsert(item)
            }
        }

    // Positions are keyed locally by bookId, but the server identifies each row by a random
    // UUID the client never stores — so the client cannot reproduce the server's digest
    // identity. Opt out of digest reconciliation (positions self-heal via lastPlayedAt-wins
    // re-saves on next playback). Same rationale as active_sessions.
    override suspend fun localDigestRows(maxRevision: Long): List<Pair<String, Long>>? = null

    /**
     * Upsert the position row, applying the `lastPlayedAt`-wins policy.
     *
     * If the existing row's `lastPlayedAt` is greater than or equal to the
     * payload's `lastPlayedAt`, the local row is at least as fresh as the server
     * snapshot and this method is a no-op. The local row will push its value to
     * the server on the next sync pass.
     *
     * When the event IS applied, local-only columns (`hasCustomSpeed`, `syncedAt`,
     * `finishedAt`, `startedAt`) are copied from the existing row so a sync event
     * never nulls client-only data.
     */
    private suspend fun upsert(payload: PlaybackPositionSyncPayload) {
        val existing = database.playbackPositionDao().get(BookId(payload.bookId))

        // lastPlayedAt-wins: reject stale server snapshots and own-echo events
        if (existing != null &&
            existing.lastPlayedAt != null &&
            existing.lastPlayedAt >= payload.lastPlayedAt
        ) {
            return
        }

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
}
