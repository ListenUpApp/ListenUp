package com.calypsan.listenup.client.data.sync.handlers

import com.calypsan.listenup.api.sync.ActiveSessionSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.client.data.local.db.ActiveSessionEntity
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.sync.ClientSyncDomainRegistry
import com.calypsan.listenup.client.data.sync.SyncDomainHandler
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Client-side sync handler for the `active_sessions` domain (Playback-P3).
 *
 * Applies server sync events into the Room `active_sessions` table so the
 * "currently listening" section in BookDetail and the Discover screen react
 * to server-side session changes without polling.
 *
 * **Upsert on Created/Updated.** The server is the authority for who is
 * currently listening — the client reflects the server state unconditionally.
 *
 * **Deleted events.** The completion cascade in
 * [com.calypsan.listenup.server.services.ActiveSessionRepository.deleteForUserBook]
 * soft-deletes the server-side row and publishes a [SyncEvent.Deleted] SSE event.
 * On receipt, this handler hard-deletes the client-side Room row by its `sessionId`
 * — the Room entity has no `deletedAt` column because active sessions are ephemeral
 * data that should not accumulate tombstones locally.
 *
 * **Catch-up tombstones.** A tombstone arriving via [onCatchUpItem] (`deletedAt`
 * non-null) is treated identically: the local row is hard-deleted.
 *
 * Self-registers in [ClientSyncDomainRegistry] at construction.
 */
class ActiveSessionSyncDomainHandler(
    private val database: ListenUpDatabase,
    private val transactionRunner: TransactionRunner,
    registry: ClientSyncDomainRegistry,
) : SyncDomainHandler<ActiveSessionSyncPayload> {
    override val domainName: String = "active_sessions"
    override val payloadSerializer = ActiveSessionSyncPayload.serializer()

    override fun syncId(item: ActiveSessionSyncPayload): String = item.sessionId

    init {
        registry.register(this)
    }

    override suspend fun onEvent(
        event: SyncEvent<ActiveSessionSyncPayload>,
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
                    // Soft-delete published by the server's completion cascade
                    // (deleteForUserBook) or session-end event. Hard-delete locally
                    // because the Room entity has no deletedAt column.
                    database.activeSessionDao().deleteBySessionId(event.id)
                    logger.debug { "[$domainName] Deleted session ${event.id}" }
                }
            }
        }

    override suspend fun onCatchUpItem(
        item: ActiveSessionSyncPayload,
        isTombstone: Boolean,
    ): AppResult<Unit> =
        transactionRunner.applyEventAtomically(domainName, item.sessionId, logger) {
            if (isTombstone) {
                database.activeSessionDao().deleteBySessionId(item.sessionId)
            } else {
                upsert(item)
            }
        }

    /**
     * Active sessions are ephemeral — the [ActiveSessionEntity] table carries no `revision`
     * column, so there is nothing to fingerprint. Digest reconciliation is not applicable
     * to this domain; always returns an empty list.
     */
    override suspend fun localDigestRows(maxRevision: Long): List<Pair<String, Long>> = emptyList()

    private suspend fun upsert(payload: ActiveSessionSyncPayload) {
        database.activeSessionDao().upsert(
            ActiveSessionEntity(
                sessionId = payload.sessionId,
                userId = "", // userId is not carried in the wire payload; populated by server JOIN at read time
                bookId = payload.bookId,
                startedAt = payload.startedAt,
                updatedAt = payload.updatedAt,
            ),
        )
    }
}
