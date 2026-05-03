package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.core.BookId
import com.calypsan.listenup.client.core.Timestamp
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.client.data.local.db.BookEntity
import com.calypsan.listenup.client.data.local.db.EntityType
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.OperationType
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.local.db.ShelfBookCrossRef
import com.calypsan.listenup.client.data.local.db.ShelfEntity
import com.calypsan.listenup.client.data.local.db.SyncState
import com.calypsan.listenup.client.data.local.db.UserEntity
import com.calypsan.listenup.client.data.remote.ShelfApiContract
import com.calypsan.listenup.client.data.sync.push.AddBooksToShelfHandler
import com.calypsan.listenup.client.data.sync.push.CreateShelfHandler
import com.calypsan.listenup.client.data.sync.push.DeleteShelfHandler
import com.calypsan.listenup.client.data.sync.push.PendingOperationRepositoryContract
import com.calypsan.listenup.client.data.sync.push.RemoveBookFromShelfHandler
import com.calypsan.listenup.client.data.sync.push.UpdateShelfHandler
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import dev.mokkery.answering.throws
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Seam-level tests for [ShelfRepositoryImpl]'s 5 command methods.
 *
 * Each method is tested for:
 * 1. Happy path — Room row written + pending-op queued with correct type/entityId.
 * 2. Transaction atomicity — when the queue step throws, the Room write rolls back.
 *
 * Uses a real in-memory [ListenUpDatabase] with a real [RoomTransactionRunner] so
 * transaction rollback is exercised end-to-end. Handlers are real instances (they
 * are final classes) backed by a mocked [ShelfApiContract] — the API is never called
 * inside the `atomically` block, so the mock is never touched. The
 * [PendingOperationRepositoryContract] is mocked only for the atomicity tests where
 * we need `queue` to throw.
 */
class ShelfRepositoryImplCommandTest {
    private val db: ListenUpDatabase = createInMemoryTestDatabase()
    private val shelfApi: ShelfApiContract = mock()

    @AfterTest
    fun tearDown() {
        db.close()
    }

    // -------------------------------------------------------------------------
    // createShelf
    // -------------------------------------------------------------------------

    @Test
    fun `createShelf writes entity to Room and queues CREATE_SHELF pending op`() =
        runTest {
            seedUser()

            val repo = createRepo()
            val shelf = repo.createShelf(name = "Fantasy", description = "Epic reads")

            // Room has the new shelf
            val stored = db.shelfDao().getById(shelf.id)
            assertNotNull(stored, "shelf must be persisted to Room")
            assertEquals("Fantasy", stored.name)
            assertEquals("Epic reads", stored.description)
            assertEquals(SyncState.NOT_SYNCED, stored.syncState)

            // A pending op was queued
            val ops = db.pendingOperationDao().getOldestPending()
            assertNotNull(ops, "a pending operation must have been queued")
            assertEquals(OperationType.CREATE_SHELF, ops.operationType)
            assertEquals(shelf.id, ops.entityId)
            assertEquals(EntityType.SHELF, ops.entityType)
        }

    @Test
    fun `createShelf returns Shelf domain model with correct fields`() =
        runTest {
            seedUser(displayName = "Simon")

            val repo = createRepo()
            val shelf = repo.createShelf(name = "Sci-Fi", description = null)

            assertEquals("Sci-Fi", shelf.name)
            assertNull(shelf.description)
            assertTrue(shelf.id.isNotEmpty())
        }

    @Test
    fun `createShelf rolls back Room write when queue throws`() =
        runTest {
            seedUser()

            val repo = createRepo(pendingOpQueueThrows = true)
            val shelfId =
                runCatching { repo.createShelf(name = "Horror", description = null) }
                    .fold(onSuccess = { it.id }, onFailure = { null })

            // If the throw propagated, shelfId is null; if somehow set, Room must be empty
            val stored = shelfId?.let { db.shelfDao().getById(it) }
            assertNull(stored, "Room write must roll back when queue throws")

            // No pending ops either
            assertNull(db.pendingOperationDao().getOldestPending(), "no pending op after rollback")
        }

    // -------------------------------------------------------------------------
    // updateShelf
    // -------------------------------------------------------------------------

    @Test
    fun `updateShelf writes updated entity to Room and queues UPDATE_SHELF pending op`() =
        runTest {
            val shelfId = seedShelf(name = "Old Name")

            val repo = createRepo()
            repo.updateShelf(shelfId, name = "New Name", description = "Updated desc")

            val stored = db.shelfDao().getById(shelfId)
            assertNotNull(stored)
            assertEquals("New Name", stored.name)
            assertEquals("Updated desc", stored.description)
            assertEquals(SyncState.NOT_SYNCED, stored.syncState)

            val ops = db.pendingOperationDao().getOldestPending()
            assertNotNull(ops)
            assertEquals(OperationType.UPDATE_SHELF, ops.operationType)
            assertEquals(shelfId, ops.entityId)
        }

    @Test
    fun `updateShelf returns updated Shelf domain model`() =
        runTest {
            val shelfId = seedShelf(name = "Original")

            val repo = createRepo()
            val result = repo.updateShelf(shelfId, name = "Revised", description = "New desc")

            assertEquals("Revised", result.name)
            assertEquals("New desc", result.description)
        }

    @Test
    fun `updateShelf rolls back Room write when queue throws`() =
        runTest {
            val shelfId = seedShelf(name = "Stable Name")

            val repo = createRepo(pendingOpQueueThrows = true)
            runCatching { repo.updateShelf(shelfId, name = "Changed", description = null) }

            val stored = db.shelfDao().getById(shelfId)
            assertNotNull(stored)
            assertEquals("Stable Name", stored.name, "name must roll back")
            assertEquals(SyncState.SYNCED, stored.syncState, "syncState must roll back")
        }

    // -------------------------------------------------------------------------
    // deleteShelf
    // -------------------------------------------------------------------------

    @Test
    fun `deleteShelf removes entity from Room and queues DELETE_SHELF pending op`() =
        runTest {
            val shelfId = seedShelf(name = "To Delete")

            val repo = createRepo()
            repo.deleteShelf(shelfId)

            assertNull(db.shelfDao().getById(shelfId), "shelf must be removed from Room")

            val ops = db.pendingOperationDao().getOldestPending()
            assertNotNull(ops)
            assertEquals(OperationType.DELETE_SHELF, ops.operationType)
            assertEquals(shelfId, ops.entityId)
        }

    @Test
    fun `deleteShelf rolls back Room delete when queue throws`() =
        runTest {
            val shelfId = seedShelf(name = "Should Survive")

            val repo = createRepo(pendingOpQueueThrows = true)
            runCatching { repo.deleteShelf(shelfId) }

            // Shelf must still exist after rollback
            assertNotNull(db.shelfDao().getById(shelfId), "shelf deletion must roll back")
            assertNull(db.pendingOperationDao().getOldestPending(), "no pending op after rollback")
        }

    // -------------------------------------------------------------------------
    // addBooksToShelf
    // -------------------------------------------------------------------------

    @Test
    fun `addBooksToShelf inserts junction rows and queues ADD_BOOKS_TO_SHELF pending op`() =
        runTest {
            val shelfId = seedShelf(name = "Reading List")
            seedBook("book-1")
            seedBook("book-2")

            val repo = createRepo()
            repo.addBooksToShelf(shelfId, bookIds = listOf("book-1", "book-2"))

            val bookIds = db.shelfBookDao().getShelfBookIds(shelfId)
            assertEquals(2, bookIds.size, "two junction rows must be written")
            assertTrue("book-1" in bookIds)
            assertTrue("book-2" in bookIds)

            val ops = db.pendingOperationDao().getOldestPending()
            assertNotNull(ops)
            assertEquals(OperationType.ADD_BOOKS_TO_SHELF, ops.operationType)
            assertEquals(shelfId, ops.entityId)
        }

    @Test
    fun `addBooksToShelf rolls back junction writes when queue throws`() =
        runTest {
            val shelfId = seedShelf(name = "Protected Shelf")
            seedBook("book-99")

            val repo = createRepo(pendingOpQueueThrows = true)
            runCatching { repo.addBooksToShelf(shelfId, bookIds = listOf("book-99")) }

            val bookIds = db.shelfBookDao().getShelfBookIds(shelfId)
            assertEquals(0, bookIds.size, "junction insert must roll back")
        }

    // -------------------------------------------------------------------------
    // removeBookFromShelf
    // -------------------------------------------------------------------------

    @Test
    fun `removeBookFromShelf deletes junction row and queues REMOVE_BOOK_FROM_SHELF pending op`() =
        runTest {
            val shelfId = seedShelf(name = "My Shelf")
            seedBook("book-a")
            seedBook("book-b")
            seedShelfBook(shelfId = shelfId, bookId = "book-a")
            seedShelfBook(shelfId = shelfId, bookId = "book-b")

            val repo = createRepo()
            repo.removeBookFromShelf(shelfId, bookId = "book-a")

            val bookIds = db.shelfBookDao().getShelfBookIds(shelfId)
            assertEquals(1, bookIds.size, "only one junction row must remain")
            assertTrue("book-b" in bookIds, "book-b must still be present")

            val ops = db.pendingOperationDao().getOldestPending()
            assertNotNull(ops)
            assertEquals(OperationType.REMOVE_BOOK_FROM_SHELF, ops.operationType)
            assertEquals(shelfId, ops.entityId)
        }

    @Test
    fun `removeBookFromShelf rolls back junction delete when queue throws`() =
        runTest {
            val shelfId = seedShelf(name = "Protected")
            seedBook("book-x")
            seedShelfBook(shelfId = shelfId, bookId = "book-x")

            val repo = createRepo(pendingOpQueueThrows = true)
            runCatching { repo.removeBookFromShelf(shelfId, bookId = "book-x") }

            val bookIds = db.shelfBookDao().getShelfBookIds(shelfId)
            assertEquals(1, bookIds.size, "junction delete must roll back")
            assertTrue("book-x" in bookIds, "book-x must be restored after rollback")
        }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private suspend fun seedUser(
        id: String = "user-1",
        displayName: String = "Test User",
        avatarColor: String = "#6B7280",
    ) {
        db.userDao().upsert(
            UserEntity(
                id = UserId(id),
                email = "test@example.com",
                displayName = displayName,
                isRoot = false,
                createdAt = Timestamp(1L),
                updatedAt = Timestamp(1L),
                avatarColor = avatarColor,
            ),
        )
    }

    private suspend fun seedShelf(
        id: String = "shelf-seed-${System.nanoTime()}",
        name: String,
    ): String {
        db.shelfDao().upsert(
            ShelfEntity(
                id = id,
                name = name,
                description = null,
                ownerId = "user-1",
                ownerDisplayName = "Test User",
                ownerAvatarColor = "#6B7280",
                bookCount = 0,
                totalDurationSeconds = 0L,
                createdAt = Timestamp(1L),
                updatedAt = Timestamp(1L),
                syncState = SyncState.SYNCED,
            ),
        )
        return id
    }

    private suspend fun seedShelfBook(
        shelfId: String,
        bookId: String,
    ) {
        db.shelfBookDao().upsert(
            ShelfBookCrossRef(shelfId = shelfId, bookId = bookId, addedAt = System.currentTimeMillis()),
        )
    }

    private suspend fun seedBook(id: String) {
        db.bookDao().upsert(
            BookEntity(
                id = BookId(id),
                title = "Book $id",
                coverUrl = null,
                totalDuration = 0L,
                syncState = SyncState.SYNCED,
                lastModified = Timestamp(1L),
                serverVersion = Timestamp(1L),
                createdAt = Timestamp(1L),
                updatedAt = Timestamp(1L),
            ),
        )
    }

    /**
     * Builds a [ShelfRepositoryImpl] backed by the real in-memory DB.
     *
     * When [pendingOpQueueThrows] is true, the [PendingOperationRepositoryContract] is
     * replaced with a mock whose `queue` always throws — verifying that the preceding
     * DAO write rolls back atomically.
     */
    private fun createRepo(pendingOpQueueThrows: Boolean = false): ShelfRepositoryImpl {
        val pendingOpRepo: PendingOperationRepositoryContract =
            if (pendingOpQueueThrows) {
                mock<PendingOperationRepositoryContract>().also {
                    everySuspend {
                        it.queue<Any>(any(), any(), any(), any(), any())
                    } throws RuntimeException("boom — pending-op queue failed")
                }
            } else {
                // Use a real PendingOperationRepository backed by the in-memory DB so we
                // can assert on what was actually queued.
                com.calypsan.listenup.client.data.sync.push.PendingOperationRepository(
                    transactionRunner = RoomTransactionRunner(db),
                    dao = db.pendingOperationDao(),
                    bookDao = db.bookDao(),
                    contributorDao = db.contributorDao(),
                    seriesDao = db.seriesDao(),
                    shelfDao = db.shelfDao(),
                )
            }

        return ShelfRepositoryImpl(
            dao = db.shelfDao(),
            shelfBookDao = db.shelfBookDao(),
            userDao = db.userDao(),
            shelfApi = shelfApi,
            pendingOperationRepository = pendingOpRepo,
            transactionRunner = RoomTransactionRunner(db),
            createShelfHandler = CreateShelfHandler(api = shelfApi),
            updateShelfHandler = UpdateShelfHandler(api = shelfApi),
            deleteShelfHandler = DeleteShelfHandler(api = shelfApi),
            addBooksToShelfHandler = AddBooksToShelfHandler(api = shelfApi),
            removeBookFromShelfHandler = RemoveBookFromShelfHandler(api = shelfApi),
        )
    }
}
