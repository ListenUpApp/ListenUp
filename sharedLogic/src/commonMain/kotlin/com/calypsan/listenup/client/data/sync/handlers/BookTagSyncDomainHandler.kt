package com.calypsan.listenup.client.data.sync.handlers

import com.calypsan.listenup.api.sync.BookTagSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.client.data.local.db.BookTagEntity
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.sync.ClientSyncDomainRegistry
import com.calypsan.listenup.client.data.sync.SyncDomainHandler
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Client-side sync handler for the `book_tags` junction domain (Tags phase — Room v22).
 *
 * Applies server sync events into the Room `book_tags` table. The junction carries
 * `(bookId, tagId)` as its composite primary key; the server synthesises a stable
 * string ID for the SSE envelope in the form `"$bookId:$tagId"`.
 *
 * **Tombstone semantics.** A [SyncEvent.Deleted] event tombstones the junction row
 * by setting [BookTagEntity.deletedAt] via [com.calypsan.listenup.client.data.local.db.BookTagDao.tombstone].
 * The ID is parsed by splitting on `:` — the server guarantees this format via
 * `BookTagId.asString()`.
 *
 * **Re-add semantics.** If a tag is re-applied after removal, the server emits a
 * [SyncEvent.Created] (or [SyncEvent.Updated]) with `deletedAt = null`. The handler
 * upserts the row, clearing the tombstone.
 *
 * No FK constraints on `book_tags` — the sync handler is responsible for integrity.
 * `isOwnEcho` is passed through but not acted on; the server echo triggers the same
 * Room write as any other event, which is safe because `@Upsert` is idempotent.
 *
 * Self-registers in [ClientSyncDomainRegistry] at construction.
 */
class BookTagSyncDomainHandler(
    private val database: ListenUpDatabase,
    private val transactionRunner: TransactionRunner,
    registry: ClientSyncDomainRegistry,
) : SyncDomainHandler<BookTagSyncPayload> {
    override val domainName: String = "book_tags"
    override val payloadSerializer = BookTagSyncPayload.serializer()

    override fun syncId(item: BookTagSyncPayload): String = "${item.bookId}:${item.tagId}"

    init {
        registry.register(this)
    }

    override suspend fun onEvent(
        event: SyncEvent<BookTagSyncPayload>,
        isOwnEcho: Boolean,
    ): AppResult<Unit> =
        transactionRunner.applyEventAtomically(domainName, event.id, logger) {
            when (event) {
                is SyncEvent.Created -> upsert(event.payload)
                is SyncEvent.Updated -> upsert(event.payload)
                is SyncEvent.Deleted -> tombstoneById(event.id, event.occurredAt)
            }
        }

    override suspend fun onCatchUpItem(
        item: BookTagSyncPayload,
        isTombstone: Boolean,
    ): AppResult<Unit> =
        transactionRunner.applyEventAtomically(domainName, "${item.bookId}:${item.tagId}", logger) {
            if (isTombstone) {
                database.bookTagDao().tombstone(
                    bookId = item.bookId,
                    tagId = item.tagId,
                    deletedAt = item.deletedAt ?: item.createdAt,
                )
            } else {
                upsert(item)
            }
        }

    override suspend fun localDigestRows(maxRevision: Long): List<Pair<String, Long>> =
        database.bookTagDao().digestRows(maxRevision).map { it.id to it.revision }

    private suspend fun upsert(payload: BookTagSyncPayload) {
        database.bookTagDao().upsert(
            BookTagEntity(
                bookId = payload.bookId,
                tagId = payload.tagId,
                createdAt = payload.createdAt,
                revision = payload.revision,
                deletedAt = payload.deletedAt,
            ),
        )
    }

    /**
     * Tombstone a junction row from a [SyncEvent.Deleted] event.
     *
     * The server synthesises `"$bookId:$tagId"` as the stable SSE envelope ID
     * (see `BookTagId.asString()` server-side). The `:` delimiter cannot appear
     * in either part because book/tag IDs are UUIDv7 strings.
     */
    private suspend fun tombstoneById(
        syntheticId: String,
        occurredAt: Long,
    ) {
        val parts = syntheticId.split(":")
        if (parts.size != 2) {
            logger.warn { "book_tags Deleted event has unexpected id format: '$syntheticId' — skipping tombstone" }
            return
        }
        database.bookTagDao().tombstone(
            bookId = parts[0],
            tagId = parts[1],
            deletedAt = occurredAt,
        )
    }
}
