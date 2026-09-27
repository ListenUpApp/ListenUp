package com.calypsan.listenup.server.testing

import app.cash.sqldelight.db.SqlDriver
import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.CollectionShareSyncPayload
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.sync.CollectionGrantRepository
import com.calypsan.listenup.server.sync.CollectionRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import kotlinx.coroutines.runBlocking

/**
 * Makes [bookId] visible to [viewerId] the pure-union way: places it in the per-library
 * ALL_BOOKS system collection and grants [viewerId] a read share on it. [viewerId] MUST
 * already be FK-seeded via [seedTestUser] — `collection_grants.principal_id` references
 * `users(id)`.
 *
 * The single shared copy of a fixture that used to live, verbatim in behaviour but diverging in
 * signature, in both `SocialServiceTest` and `ShelfServiceUserShelvesTest`. Deliberately **not**
 * `suspend`: it runs its writes through its own [runBlocking] so it can be called from either a
 * plain `withSqlDatabase { }` block or from inside a `runTest { }` — both call shapes exist across
 * the two original copies, and this is the one signature compatible with both.
 *
 * Grant id is keyed on (collection, viewer), NOT the book: the per-(collection,principal) grant
 * is unique, so repeated calls for the same viewer must reuse this row (upsert).
 */
internal fun makeBookAccessible(
    sql: ListenUpDatabase,
    driver: SqlDriver,
    bookId: String,
    viewerId: String,
    grantId: String = "grant-$viewerId",
    allBooksId: String = "all-books",
) {
    val bus = ChangeBus()
    val registry = SyncRegistry()
    val collectionRepo =
        CollectionRepository(
            db = sql,
            bus = bus,
            registry = registry,
            driver = driver,
        )
    val collectionBookRepo =
        CollectionBookRepository(
            db = sql,
            bus = bus,
            registry = registry,
            driver = driver,
        )
    val grantRepo =
        CollectionGrantRepository(
            db = sql,
            bus = bus,
            registry = registry,
            driver = driver,
        )
    runBlocking {
        collectionRepo.upsert(
            CollectionSyncPayload(
                id = allBooksId,
                libraryId = "test-library",
                ownerId = "system",
                name = "All Books",
                isInbox = false,
                revision = 0L,
                updatedAt = 0L,
            ),
        )
        collectionBookRepo.upsert(
            CollectionBookSyncPayload(
                id = "$allBooksId:$bookId",
                collectionId = allBooksId,
                bookId = bookId,
                createdAt = 0L,
                revision = 0L,
            ),
        )
        grantRepo.upsert(
            CollectionShareSyncPayload(
                id = grantId,
                collectionId = allBooksId,
                sharedWithUserId = viewerId,
                sharedByUserId = "system",
                permission = SharePermission.Read,
                revision = 0L,
                updatedAt = 0L,
            ),
        )
    }
}
