package com.calypsan.listenup.client.data.sync.handlers

import com.calypsan.listenup.api.sync.Mood
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.MoodEntity
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.sync.ClientSyncDomainRegistry
import com.calypsan.listenup.client.data.sync.SyncDomainHandler
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Client-side sync handler for the `moods` domain.
 *
 * Applies server sync events into the Room `moods` table. Mood rows carry
 * the full wire payload on [SyncEvent.Created] and [SyncEvent.Updated];
 * [SyncEvent.Deleted] events soft-delete the row via
 * [com.calypsan.listenup.client.data.local.db.MoodDao.softDelete].
 *
 * `isOwnEcho` is ignored: mood mutations originate server-side (curators);
 * the client has no local mood-row write path that would generate echoes.
 *
 * Self-registers in [ClientSyncDomainRegistry] at construction. Mirrors
 * [com.calypsan.listenup.client.data.sync.TagSyncDomainHandler].
 */
class MoodSyncDomainHandler(
    private val database: ListenUpDatabase,
    private val transactionRunner: TransactionRunner,
    registry: ClientSyncDomainRegistry,
) : SyncDomainHandler<Mood> {
    override val domainName: String = "moods"
    override val payloadSerializer = Mood.serializer()

    override fun syncId(item: Mood): String = item.id

    init {
        registry.register(this)
    }

    override suspend fun onEvent(
        event: SyncEvent<Mood>,
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
                    database.moodDao().softDelete(
                        id = event.id,
                        deletedAt = event.occurredAt,
                        revision = event.revision,
                    )
                }
            }
        }

    override suspend fun onCatchUpItem(
        item: Mood,
        isTombstone: Boolean,
    ): AppResult<Unit> =
        transactionRunner.applyEventAtomically(domainName, item.id, logger) {
            if (isTombstone) {
                database.moodDao().softDelete(
                    id = item.id,
                    deletedAt = item.deletedAt ?: item.updatedAt,
                    revision = item.revision,
                )
            } else {
                upsert(item)
            }
        }

    override suspend fun localDigestRows(maxRevision: Long): List<Pair<String, Long>> =
        database.moodDao().digestRows(maxRevision).map { it.id to it.revision }

    private suspend fun upsert(payload: Mood) {
        database.moodDao().upsert(
            MoodEntity(
                id = payload.id,
                name = payload.name,
                slug = payload.slug,
                revision = payload.revision,
                deletedAt = payload.deletedAt,
                updatedAt = payload.updatedAt,
            ),
        )
    }
}
