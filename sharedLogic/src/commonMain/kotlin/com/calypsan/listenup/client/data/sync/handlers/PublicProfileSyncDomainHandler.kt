package com.calypsan.listenup.client.data.sync.handlers

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.PublicProfileSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.PublicProfileEntity
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.sync.ClientSyncDomainRegistry
import com.calypsan.listenup.client.data.sync.SyncDomainHandler
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Client-side sync handler for the global `public_profiles` domain.
 *
 * Each row is a server-maintained materialized view of a user's public social identity;
 * the client always replaces its local row unconditionally (server-wins, no client-writable
 * fields). Unlike [UserStatsSyncDomainHandler], this domain tombstones (deleted users):
 * [SyncEvent.Deleted] events are applied immediately via [softDelete][com.calypsan.listenup.client.data.local.db.PublicProfileDao.softDelete].
 *
 * Self-registers in [ClientSyncDomainRegistry] at construction.
 */
internal class PublicProfileSyncDomainHandler(
    private val database: ListenUpDatabase,
    private val transactionRunner: TransactionRunner,
    registry: ClientSyncDomainRegistry,
) : SyncDomainHandler<PublicProfileSyncPayload> {
    override val domainName: String = "public_profiles"
    override val payloadSerializer = PublicProfileSyncPayload.serializer()

    override fun syncId(item: PublicProfileSyncPayload): String = item.id

    init {
        registry.register(this)
    }

    override suspend fun onEvent(
        event: SyncEvent<PublicProfileSyncPayload>,
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
                    database.publicProfileDao().softDelete(
                        id = event.id,
                        deletedAt = event.occurredAt,
                        revision = event.revision,
                    )
                }
            }
        }

    override suspend fun onCatchUpItem(
        item: PublicProfileSyncPayload,
        isTombstone: Boolean,
    ): AppResult<Unit> =
        transactionRunner.applyEventAtomically(domainName, item.id, logger) {
            if (isTombstone) {
                database.publicProfileDao().softDelete(
                    id = item.id,
                    deletedAt = item.deletedAt ?: item.updatedAt,
                    revision = item.revision,
                )
            } else {
                upsert(item)
            }
        }

    override suspend fun localDigestRows(maxRevision: Long): List<Pair<String, Long>> =
        database.publicProfileDao().digestRows(maxRevision).map { it.id to it.revision }

    /**
     * Unconditionally upsert [payload] into [PublicProfileEntity]. The server is the
     * sole writer — no merge logic, no local-only columns to preserve.
     */
    private suspend fun upsert(payload: PublicProfileSyncPayload) {
        database.publicProfileDao().upsert(
            PublicProfileEntity(
                id = payload.id,
                displayName = payload.displayName,
                avatarType = payload.avatarType,
                tagline = payload.tagline,
                totalSecondsAllTime = payload.totalSecondsAllTime,
                totalSecondsLast7Days = payload.totalSecondsLast7Days,
                totalSecondsLast30Days = payload.totalSecondsLast30Days,
                totalSecondsLast365Days = payload.totalSecondsLast365Days,
                booksFinished = payload.booksFinished,
                currentStreakDays = payload.currentStreakDays,
                longestStreakDays = payload.longestStreakDays,
                booksFinishedLast7Days = payload.booksFinishedLast7Days,
                booksFinishedLast30Days = payload.booksFinishedLast30Days,
                booksFinishedLast365Days = payload.booksFinishedLast365Days,
                longestStreakLast7Days = payload.longestStreakLast7Days,
                longestStreakLast30Days = payload.longestStreakLast30Days,
                longestStreakLast365Days = payload.longestStreakLast365Days,
                revision = payload.revision,
                deletedAt = payload.deletedAt,
            ),
        )
    }
}
