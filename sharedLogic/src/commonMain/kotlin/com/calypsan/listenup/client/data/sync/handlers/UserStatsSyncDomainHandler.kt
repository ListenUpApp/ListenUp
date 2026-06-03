package com.calypsan.listenup.client.data.sync.handlers

import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.sync.UserStatsSyncPayload
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.local.db.UserStatsEntity
import com.calypsan.listenup.client.data.sync.ClientSyncDomainRegistry
import com.calypsan.listenup.client.data.sync.SyncDomainHandler
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Client-side sync handler for the `user_stats` domain (Playback-P2).
 *
 * Applies server sync events into the Room `user_stats` table. Each row is a
 * **server-maintained materialized view** — the server is the sole writer of
 * domain fields, and the client always replaces its local row unconditionally.
 *
 * **Server-wins conflict policy.** Unlike listening events (append-only) or
 * playback positions (lastPlayedAt-wins), user stats have no client-writable fields
 * — the server computes everything from the canonical event log. An incoming event
 * therefore unconditionally replaces the local row. There are no local-only columns
 * to preserve.
 *
 * **Tombstone handling.** The server does not emit user-stats tombstones in P2
 * (the row exists for the lifetime of the user). Tombstones received via
 * [onCatchUpItem] apply a soft-delete by `id`; [SyncEvent.Deleted] at the SSE
 * level is handled defensively with a log.
 *
 * Self-registers in [ClientSyncDomainRegistry] at construction.
 */
class UserStatsSyncDomainHandler(
    private val database: ListenUpDatabase,
    private val transactionRunner: TransactionRunner,
    registry: ClientSyncDomainRegistry,
) : SyncDomainHandler<UserStatsSyncPayload> {
    override val domainName: String = "user_stats"
    override val payloadSerializer = UserStatsSyncPayload.serializer()

    override fun syncId(item: UserStatsSyncPayload): String = item.id

    init {
        registry.register(this)
    }

    override suspend fun onEvent(
        event: SyncEvent<UserStatsSyncPayload>,
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
                    logger.debug { "[$domainName] Deleted event for ${event.id} — deferred to catch-up" }
                }
            }
        }

    override suspend fun onCatchUpItem(
        item: UserStatsSyncPayload,
        isTombstone: Boolean,
    ): AppResult<Unit> =
        transactionRunner.applyEventAtomically(domainName, item.id, logger) {
            if (isTombstone) {
                database.userStatsDao().softDelete(
                    id = item.id,
                    deletedAt = item.deletedAt ?: item.updatedAt,
                    revision = item.revision,
                )
            } else {
                upsert(item)
            }
        }

    override suspend fun localDigestRows(maxRevision: Long): List<Pair<String, Long>> =
        database.userStatsDao().digestRows(maxRevision).map { it.id to it.revision }

    /**
     * Unconditionally upsert [payload] into [UserStatsEntity]. The server is the
     * sole writer of stats — no merge logic, no local-only columns to preserve.
     */
    private suspend fun upsert(payload: UserStatsSyncPayload) {
        database.userStatsDao().upsert(
            UserStatsEntity(
                id = payload.id,
                totalSecondsAllTime = payload.totalSecondsAllTime,
                totalSecondsLast7Days = payload.totalSecondsLast7Days,
                totalSecondsLast30Days = payload.totalSecondsLast30Days,
                booksStarted = payload.booksStarted,
                booksFinished = payload.booksFinished,
                currentStreakDays = payload.currentStreakDays,
                longestStreakDays = payload.longestStreakDays,
                lastEventDate = payload.lastEventDate,
                revision = payload.revision,
                deletedAt = payload.deletedAt,
            ),
        )
    }
}
