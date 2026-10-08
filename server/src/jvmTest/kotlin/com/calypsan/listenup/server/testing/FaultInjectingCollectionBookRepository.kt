package com.calypsan.listenup.server.testing

import app.cash.sqldelight.db.SqlDriver
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.sync.SyncRegistry

/**
 * A [CollectionBookRepository] that faults membership writes for chosen `(collectionId, bookId)`
 * pairs, delegating every other pair to the real write untouched. Two fault shapes:
 *
 * - [failingPairs]: [upsert] returns `Failure` without reaching the database — the shape #1226
 *   describes for `setBookCollections`.
 * - [throwingPairs]: the row write itself THROWS from inside the open transaction, which is how a
 *   real SQLite fault surfaces — the transaction rolls back and the exception propagates. Every
 *   write path that reaches [writePayload] sees it, including in-transaction batch writes.
 */
class FaultInjectingCollectionBookRepository(
    db: ListenUpDatabase,
    bus: ChangeBus,
    registry: SyncRegistry,
    driver: SqlDriver,
    private val failingPairs: Set<Pair<String, String>> = emptySet(),
    private val throwingPairs: Set<Pair<String, String>> = emptySet(),
) : CollectionBookRepository(db = db, bus = bus, registry = registry, driver = driver) {
    override suspend fun upsert(
        value: CollectionBookSyncPayload,
        clientOpId: String?,
        userId: String?,
    ): AppResult<CollectionBookSyncPayload> =
        if (value.collectionId to value.bookId in failingPairs) {
            AppResult.Failure(SyncError.PushFailed(debugInfo = "injected test failure"))
        } else {
            super.upsert(value, clientOpId, userId)
        }

    override fun writePayload(
        value: CollectionBookSyncPayload,
        rev: Long,
        now: Long,
        clientOpId: String?,
        userId: String?,
        existed: Boolean,
    ) {
        if (value.collectionId to value.bookId in throwingPairs) {
            error("injected write fault for ${value.collectionId}:${value.bookId}")
        }
        super.writePayload(value, rev, now, clientOpId, userId, existed)
    }
}
