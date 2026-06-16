package com.calypsan.listenup.client.data.sync.handlers

import com.calypsan.listenup.api.sync.BookMoodSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.BookMoodEntity
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.sync.ClientSyncDomainRegistry
import com.calypsan.listenup.client.data.sync.SyncDomainHandler
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Client-side sync handler for the `book_moods` junction domain.
 *
 * Applies server sync events into the Room `book_moods` table. The junction carries
 * `(bookId, moodId)` as its composite primary key; the server synthesises a stable
 * string ID for the SSE envelope in the form `"$bookId:$moodId"`.
 *
 * **Tombstone semantics.** A [SyncEvent.Deleted] event tombstones the junction row
 * by setting [BookMoodEntity.deletedAt] via
 * [com.calypsan.listenup.client.data.local.db.BookMoodDao.tombstone]. The ID is parsed
 * by splitting on `:` — the server guarantees this format.
 *
 * **Re-add semantics.** If a mood is re-applied after removal, the server emits a
 * [SyncEvent.Created] (or [SyncEvent.Updated]) with `deletedAt = null`. The handler
 * upserts the row, clearing the tombstone.
 *
 * No FK constraints on `book_moods` — the sync handler is responsible for integrity.
 * `isOwnEcho` is passed through but not acted on; the server echo triggers the same
 * Room write as any other event, which is safe because `@Upsert` is idempotent.
 *
 * Self-registers in [ClientSyncDomainRegistry] at construction. Mirrors
 * [BookTagSyncDomainHandler].
 */
class BookMoodSyncDomainHandler(
    private val database: ListenUpDatabase,
    private val transactionRunner: TransactionRunner,
    registry: ClientSyncDomainRegistry,
) : SyncDomainHandler<BookMoodSyncPayload> {
    override val domainName: String = "book_moods"
    override val payloadSerializer = BookMoodSyncPayload.serializer()

    override fun syncId(item: BookMoodSyncPayload): String = "${item.bookId}:${item.moodId}"

    init {
        registry.register(this)
    }

    override suspend fun onEvent(
        event: SyncEvent<BookMoodSyncPayload>,
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
        item: BookMoodSyncPayload,
        isTombstone: Boolean,
    ): AppResult<Unit> =
        transactionRunner.applyEventAtomically(domainName, "${item.bookId}:${item.moodId}", logger) {
            if (isTombstone) {
                database.bookMoodDao().tombstone(
                    bookId = item.bookId,
                    moodId = item.moodId,
                    deletedAt = item.deletedAt ?: item.createdAt,
                )
            } else {
                upsert(item)
            }
        }

    override suspend fun localDigestRows(maxRevision: Long): List<Pair<String, Long>> =
        database.bookMoodDao().digestRows(maxRevision).map { it.id to it.revision }

    private suspend fun upsert(payload: BookMoodSyncPayload) {
        database.bookMoodDao().upsert(
            BookMoodEntity(
                bookId = payload.bookId,
                moodId = payload.moodId,
                createdAt = payload.createdAt,
                revision = payload.revision,
                deletedAt = payload.deletedAt,
            ),
        )
    }

    /**
     * Tombstone a junction row from a [SyncEvent.Deleted] event.
     *
     * The server synthesises `"$bookId:$moodId"` as the stable SSE envelope ID.
     * The `:` delimiter cannot appear in either part because book/mood IDs are
     * UUIDv7 strings.
     */
    private suspend fun tombstoneById(
        syntheticId: String,
        occurredAt: Long,
    ) {
        val parts = syntheticId.split(":")
        if (parts.size != 2) {
            logger.warn { "book_moods Deleted event has unexpected id format: '$syntheticId' — skipping tombstone" }
            return
        }
        database.bookMoodDao().tombstone(
            bookId = parts[0],
            moodId = parts[1],
            deletedAt = occurredAt,
        )
    }
}
