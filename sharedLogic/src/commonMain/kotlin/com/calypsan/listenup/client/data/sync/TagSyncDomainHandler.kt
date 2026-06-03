package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.TagEntity
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.sync.handlers.applyEventAtomically
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Client-side sync handler for the `tags` domain (Tags phase — Room v22).
 *
 * Applies server sync events into the Room `tags` table. Tag rows carry
 * the full wire payload on [Created] and [Updated]; [Deleted] events
 * soft-delete the row via [com.calypsan.listenup.client.data.local.db.TagDao.softDelete].
 *
 * `isOwnEcho` is ignored: tag mutations originate server-side (curators);
 * the client has no local tag-row write path that would generate echoes.
 *
 * Self-registers in [ClientSyncDomainRegistry] at construction.
 */
class TagSyncDomainHandler(
    private val database: ListenUpDatabase,
    private val transactionRunner: TransactionRunner,
    registry: ClientSyncDomainRegistry,
) : SyncDomainHandler<Tag> {
    override val domainName: String = "tags"
    override val payloadSerializer = Tag.serializer()

    override fun syncId(item: Tag): String = item.id

    init {
        registry.register(this)
    }

    override suspend fun onEvent(
        event: SyncEvent<Tag>,
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
                    database.tagDao().softDelete(
                        id = event.id,
                        deletedAt = event.occurredAt,
                        revision = event.revision,
                    )
                }
            }
        }

    override suspend fun onCatchUpItem(
        item: Tag,
        isTombstone: Boolean,
    ): AppResult<Unit> =
        transactionRunner.applyEventAtomically(domainName, item.id, logger) {
            if (isTombstone) {
                database.tagDao().softDelete(
                    id = item.id,
                    deletedAt = item.deletedAt ?: item.updatedAt,
                    revision = item.revision,
                )
            } else {
                upsert(item)
            }
        }

    override suspend fun localDigestRows(maxRevision: Long): List<Pair<String, Long>> =
        database.tagDao().digestRows(maxRevision).map { it.id to it.revision }

    private suspend fun upsert(payload: Tag) {
        database.tagDao().upsert(
            TagEntity(
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
