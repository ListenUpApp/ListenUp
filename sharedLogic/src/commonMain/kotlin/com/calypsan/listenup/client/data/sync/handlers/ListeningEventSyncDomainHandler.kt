package com.calypsan.listenup.client.data.sync.handlers

import com.calypsan.listenup.api.sync.ListeningEventSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.ListeningEventEntity
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.sync.ClientSyncDomainRegistry
import com.calypsan.listenup.client.data.sync.SyncDomainHandler
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Client-side sync handler for the `listening_events` domain (Playback-P2).
 *
 * Applies server sync events into the Room `listening_events` table. Each row is a
 * closed, uninterrupted listening span; the domain is **append-only** — no merge
 * logic and no local-only columns to preserve.
 *
 * **Append-only conflict policy.** A row identified by [ListeningEventSyncPayload.id]
 * is written exactly once. If the id already exists in Room — whether from a prior
 * SSE event, a catch-up pass, or the client's own tentative-span promotion — the
 * incoming event is a no-op. The existing row is authoritative; server updates to
 * listening-event rows are not propagated to the client (the server never mutates
 * domain fields on a committed span).
 *
 * **Tombstone handling.** The server does not emit listening-event tombstones in P2,
 * but the substrate interface requires handling them. A tombstone received via
 * [onCatchUpItem] applies a soft-delete by `id` and revision; a [SyncEvent.Deleted]
 * at the SSE level is a no-op (same reasoning as [PlaybackPositionSyncDomainHandler]'s
 * Deleted handling — the catch-up pass converges it).
 *
 * Self-registers in [ClientSyncDomainRegistry] at construction.
 */
class ListeningEventSyncDomainHandler(
    private val database: ListenUpDatabase,
    private val transactionRunner: TransactionRunner,
    registry: ClientSyncDomainRegistry,
) : SyncDomainHandler<ListeningEventSyncPayload> {
    override val domainName: String = "listening_events"
    override val payloadSerializer = ListeningEventSyncPayload.serializer()

    init {
        registry.register(this)
    }

    override suspend fun onEvent(
        event: SyncEvent<ListeningEventSyncPayload>,
        isOwnEcho: Boolean,
    ): AppResult<Unit> =
        transactionRunner.applyEventAtomically(domainName, event.id, logger) {
            when (event) {
                is SyncEvent.Created -> insertIfAbsent(event.payload)
                is SyncEvent.Updated -> insertIfAbsent(event.payload)
                is SyncEvent.Deleted -> {
                    // Tombstones converge via onCatchUpItem (which carries the full payload).
                    logger.debug { "[$domainName] Deleted event for ${event.id} — deferred to catch-up" }
                }
            }
        }

    override suspend fun onCatchUpItem(
        item: ListeningEventSyncPayload,
        isTombstone: Boolean,
    ): AppResult<Unit> =
        transactionRunner.applyEventAtomically(domainName, item.id, logger) {
            if (isTombstone) {
                database.listeningEventDao().softDelete(
                    id = item.id,
                    deletedAt = item.deletedAt ?: item.updatedAt,
                    revision = item.revision,
                )
            } else {
                insertIfAbsent(item)
            }
        }

    /**
     * Insert [payload] as a new [ListeningEventEntity] only if no row with the
     * same id already exists. Append-only: the caller must never overwrite an
     * existing row's domain fields.
     */
    private suspend fun insertIfAbsent(payload: ListeningEventSyncPayload) {
        val existing = database.listeningEventDao().getById(payload.id)
        if (existing != null) return

        database.listeningEventDao().upsert(
            ListeningEventEntity(
                id = payload.id,
                userId = "",  // userId is not on the wire payload; the server owns it
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
}
