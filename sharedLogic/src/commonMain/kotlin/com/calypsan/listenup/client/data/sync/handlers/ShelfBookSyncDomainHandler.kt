package com.calypsan.listenup.client.data.sync.handlers

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.ShelfBookSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.ShelfBookEntity
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.sync.ClientSyncDomainRegistry
import com.calypsan.listenup.client.data.sync.SyncDomainHandler
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Client-side sync handler for the `shelf_books` junction domain (Shelves — Room v26).
 *
 * Applies server sync events into the Room `shelf_books` table. The junction carries the
 * synthetic stable id `"$shelfId:$bookId"` the server uses on the SSE envelope; that same
 * id is the local primary key, so events apply by id alone.
 *
 * Shelf membership is user-scoped own-data — a plain [SyncDomainHandler], no access-reconcile
 * (the [CollectionBookSyncDomainHandler] is access-filtered; shelves are not).
 *
 * **Tombstone semantics.** A [SyncEvent.Deleted] event soft-deletes the junction row via
 * [com.calypsan.listenup.client.data.local.db.ShelfBookDao.softDelete].
 *
 * **Re-add semantics.** Re-adding a book after removal arrives as a [SyncEvent.Created] (or
 * [SyncEvent.Updated]) with `deletedAt = null`; the handler upserts, clearing the tombstone.
 *
 * `isOwnEcho` is passed through but not acted on; `@Upsert` is idempotent.
 *
 * Self-registers in [ClientSyncDomainRegistry] at construction.
 */
internal class ShelfBookSyncDomainHandler(
    private val database: ListenUpDatabase,
    private val transactionRunner: TransactionRunner,
    registry: ClientSyncDomainRegistry,
) : SyncDomainHandler<ShelfBookSyncPayload> {
    override val domainName: String = "shelf_books"
    override val payloadSerializer = ShelfBookSyncPayload.serializer()

    override fun syncId(item: ShelfBookSyncPayload): String = item.id

    init {
        registry.register(this)
    }

    override suspend fun onEvent(
        event: SyncEvent<ShelfBookSyncPayload>,
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
                    database.shelfBookDao().softDelete(
                        id = event.id,
                        deletedAt = event.occurredAt,
                        revision = event.revision,
                    )
                }
            }
        }

    override suspend fun onCatchUpItem(
        item: ShelfBookSyncPayload,
        isTombstone: Boolean,
    ): AppResult<Unit> =
        transactionRunner.applyEventAtomically(domainName, item.id, logger) {
            if (isTombstone) {
                database.shelfBookDao().softDelete(
                    id = item.id,
                    deletedAt = item.deletedAt ?: item.createdAt,
                    revision = item.revision,
                )
            } else {
                upsert(item)
            }
        }

    override suspend fun localDigestRows(maxRevision: Long): List<Pair<String, Long>> =
        database.shelfBookDao().digestRows(maxRevision).map { it.id to it.revision }

    private suspend fun upsert(payload: ShelfBookSyncPayload) {
        database.shelfBookDao().upsert(
            ShelfBookEntity(
                id = payload.id,
                shelfId = payload.shelfId,
                bookId = payload.bookId,
                sortOrder = payload.sortOrder,
                revision = payload.revision,
                deletedAt = payload.deletedAt,
                updatedAt = payload.updatedAt,
                createdAt = payload.createdAt,
            ),
        )
    }
}
