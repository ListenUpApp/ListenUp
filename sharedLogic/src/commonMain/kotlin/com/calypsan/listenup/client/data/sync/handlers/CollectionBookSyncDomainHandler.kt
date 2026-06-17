package com.calypsan.listenup.client.data.sync.handlers

import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.client.data.local.db.CollectionBookEntity
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.sync.AccessFilteredSyncHandler
import com.calypsan.listenup.client.data.sync.ClientSyncDomainRegistry
import com.calypsan.listenup.client.data.sync.SyncDomainHandler
import com.calypsan.listenup.api.result.AppResult
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Client-side sync handler for the `collection_books` junction domain (Collections — Room v24).
 *
 * Applies server sync events into the Room `collection_books` table. The junction carries
 * `(collectionId, bookId)` as its composite primary key; the server synthesises a stable
 * string ID for the SSE envelope in the form `"$collectionId:$bookId"` (see
 * `CollectionBookId.asString()` server-side).
 *
 * **Tombstone semantics.** A [SyncEvent.Deleted] event tombstones the junction row via
 * [com.calypsan.listenup.client.data.local.db.CollectionBookDao.tombstone]. The ID is split
 * on `:` — both parts are UUIDv7 strings, so the delimiter is unambiguous.
 *
 * **Re-add semantics.** Re-adding a book after removal arrives as a [SyncEvent.Created] (or
 * [SyncEvent.Updated]) with `deletedAt = null`; the handler upserts, clearing the tombstone.
 *
 * `isOwnEcho` is passed through but not acted on; `@Upsert` is idempotent.
 *
 * Self-registers in [ClientSyncDomainRegistry] at construction.
 */
internal class CollectionBookSyncDomainHandler(
    private val database: ListenUpDatabase,
    private val transactionRunner: TransactionRunner,
    registry: ClientSyncDomainRegistry,
) : SyncDomainHandler<CollectionBookSyncPayload>,
    AccessFilteredSyncHandler {
    override val domainName: String = "collection_books"
    override val payloadSerializer = CollectionBookSyncPayload.serializer()

    override fun syncId(item: CollectionBookSyncPayload): String = "${item.collectionId}:${item.bookId}"

    init {
        registry.register(this)
    }

    override suspend fun localLiveIds(): Set<String> = database.collectionBookDao().liveSyntheticIds().toSet()

    override suspend fun pruneTo(
        accessibleIds: Set<String>,
        now: Long,
    ) = database.collectionBookDao().tombstoneNotIn(accessibleIds, now)

    override suspend fun localDigestRows(maxRevision: Long): List<Pair<String, Long>> =
        database.collectionBookDao().digestRows(maxRevision).map { it.id to it.revision }

    override suspend fun onEvent(
        event: SyncEvent<CollectionBookSyncPayload>,
        isOwnEcho: Boolean,
    ): AppResult<Unit> =
        transactionRunner.applyEventAtomically(domainName, event.id, logger) {
            when (event) {
                is SyncEvent.Created -> upsert(event.payload)
                is SyncEvent.Updated -> upsert(event.payload)
                is SyncEvent.Deleted -> tombstoneById(event.id, event.occurredAt, event.revision)
            }
        }

    override suspend fun onCatchUpItem(
        item: CollectionBookSyncPayload,
        isTombstone: Boolean,
    ): AppResult<Unit> =
        transactionRunner.applyEventAtomically(domainName, "${item.collectionId}:${item.bookId}", logger) {
            if (isTombstone) {
                database.collectionBookDao().tombstone(
                    collectionId = item.collectionId,
                    bookId = item.bookId,
                    deletedAt = item.deletedAt ?: item.createdAt,
                    revision = item.revision,
                )
            } else {
                upsert(item)
            }
        }

    private suspend fun upsert(payload: CollectionBookSyncPayload) {
        database.collectionBookDao().upsert(
            CollectionBookEntity(
                collectionId = payload.collectionId,
                bookId = payload.bookId,
                createdAt = payload.createdAt,
                revision = payload.revision,
                deletedAt = payload.deletedAt,
            ),
        )
    }

    /**
     * Tombstone a junction row from a [SyncEvent.Deleted] event.
     *
     * The server synthesises `"$collectionId:$bookId"` as the stable SSE envelope ID. The
     * `:` delimiter cannot appear in either part because collection/book IDs are UUIDv7 strings.
     */
    private suspend fun tombstoneById(
        syntheticId: String,
        occurredAt: Long,
        revision: Long,
    ) {
        val parts = syntheticId.split(":")
        if (parts.size != 2) {
            logger.warn {
                "collection_books Deleted event has unexpected id format: '$syntheticId' — skipping tombstone"
            }
            return
        }
        database.collectionBookDao().tombstone(
            collectionId = parts[0],
            bookId = parts[1],
            deletedAt = occurredAt,
            revision = revision,
        )
    }
}
