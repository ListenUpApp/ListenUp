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
 * A [CollectionBookRepository] whose [upsert] fails for chosen `(collectionId, bookId)` pairs,
 * delegating every other pair to the real write untouched. Simulates the DB fault #1226 describes
 * — a membership write into a private collection failing mid `releaseBooks` / `setBookCollections`
 * — without a fake transaction seam of its own: a failing pair never reaches the database, so the
 * junction row is genuinely absent afterward, exactly as a real fault would leave it.
 */
class FaultInjectingCollectionBookRepository(
    db: ListenUpDatabase,
    bus: ChangeBus,
    registry: SyncRegistry,
    driver: SqlDriver,
    private val failingPairs: Set<Pair<String, String>>,
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
}
