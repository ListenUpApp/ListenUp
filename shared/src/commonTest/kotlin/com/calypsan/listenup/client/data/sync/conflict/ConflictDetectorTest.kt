package com.calypsan.listenup.client.data.sync.conflict

import com.calypsan.listenup.client.core.BookId
import com.calypsan.listenup.client.core.Timestamp
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.BookEntity
import com.calypsan.listenup.client.data.local.db.ContributorDao
import com.calypsan.listenup.client.data.local.db.ContributorEntity
import com.calypsan.listenup.client.data.local.db.EntityType
import com.calypsan.listenup.client.data.local.db.OperationStatus
import com.calypsan.listenup.client.data.local.db.OperationType
import com.calypsan.listenup.client.data.local.db.PendingOperationEntity
import com.calypsan.listenup.client.data.local.db.SeriesDao
import com.calypsan.listenup.client.data.local.db.SeriesEntity
import com.calypsan.listenup.client.data.local.db.SyncState
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.mock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for ConflictDetector.
 *
 * Verifies offline-first conflict detection logic:
 * - Server newer than local unsynced → conflict detected
 * - Local newer than server → preserve local (no conflict)
 * - Already synced books → no conflict
 * - Books not in local DB → no conflict
 */
class ConflictDetectorTest {
    private fun createBookEntity(
        id: String,
        syncState: SyncState,
        lastModified: Long,
        updatedAt: Long = lastModified,
    ): BookEntity =
        BookEntity(
            id = BookId(id),
            title = "Book $id",
            coverUrl = null,
            totalDuration = 3_600_000L,
            syncState = syncState,
            lastModified = Timestamp(lastModified),
            serverVersion = Timestamp(updatedAt),
            createdAt = Timestamp(1000L),
            updatedAt = Timestamp(updatedAt),
        )

    // ========== detectBookConflicts Tests ==========

    @Test
    fun `detectBookConflicts returns empty when no local books exist`() =
        runTest {
            // Given - server has books but local DB is empty
            val bookDao: BookDao = mock()
            val contributorDao: ContributorDao = mock()
            val seriesDao: SeriesDao = mock()
            val detector = ConflictDetector(bookDao, contributorDao, seriesDao)

            val serverBooks =
                listOf(
                    createBookEntity("book-1", SyncState.SYNCED, lastModified = 1000L, updatedAt = 2000L),
                )

            everySuspend { bookDao.getById(BookId("book-1")) } returns null

            // When
            val conflicts = detector.detectBookConflicts(serverBooks)

            // Then - no conflict because book doesn't exist locally
            assertTrue(conflicts.isEmpty())
        }

    @Test
    fun `detectBookConflicts returns empty when local book is already synced`() =
        runTest {
            // Given - local book exists but is SYNCED (no local changes)
            val bookDao: BookDao = mock()
            val contributorDao: ContributorDao = mock()
            val seriesDao: SeriesDao = mock()
            val detector = ConflictDetector(bookDao, contributorDao, seriesDao)

            val serverBook = createBookEntity("book-1", SyncState.SYNCED, lastModified = 1000L, updatedAt = 2000L)
            val localBook = createBookEntity("book-1", SyncState.SYNCED, lastModified = 1000L)

            everySuspend { bookDao.getById(BookId("book-1")) } returns localBook

            // When
            val conflicts = detector.detectBookConflicts(listOf(serverBook))

            // Then - no conflict because local is already synced
            assertTrue(conflicts.isEmpty())
        }

    @Test
    fun `detectBookConflicts detects conflict when server is newer than local unsynced`() =
        runTest {
            // Given - local has unsynced changes, but server has newer version
            val bookDao: BookDao = mock()
            val contributorDao: ContributorDao = mock()
            val seriesDao: SeriesDao = mock()
            val detector = ConflictDetector(bookDao, contributorDao, seriesDao)

            val serverBook = createBookEntity("book-1", SyncState.SYNCED, lastModified = 3000L, updatedAt = 3000L)
            val localBook = createBookEntity("book-1", SyncState.NOT_SYNCED, lastModified = 2000L)

            everySuspend { bookDao.getById(BookId("book-1")) } returns localBook

            // When
            val conflicts = detector.detectBookConflicts(listOf(serverBook))

            // Then - conflict detected: server (3000) > local (2000)
            assertEquals(1, conflicts.size)
            assertEquals(BookId("book-1"), conflicts[0].bookId)
            assertEquals(Timestamp(3000L), conflicts[0].serverTimestamp)
        }

    @Test
    fun `detectBookConflicts returns empty when local unsynced is newer than server`() =
        runTest {
            // Given - local has unsynced changes that are NEWER than server
            val bookDao: BookDao = mock()
            val contributorDao: ContributorDao = mock()
            val seriesDao: SeriesDao = mock()
            val detector = ConflictDetector(bookDao, contributorDao, seriesDao)

            val serverBook = createBookEntity("book-1", SyncState.SYNCED, lastModified = 2000L, updatedAt = 2000L)
            val localBook = createBookEntity("book-1", SyncState.NOT_SYNCED, lastModified = 3000L)

            everySuspend { bookDao.getById(BookId("book-1")) } returns localBook

            // When
            val conflicts = detector.detectBookConflicts(listOf(serverBook))

            // Then - no conflict: local (3000) >= server (2000)
            assertTrue(conflicts.isEmpty())
        }

    @Test
    fun `detectBookConflicts returns empty when timestamps are equal`() =
        runTest {
            // Given - local and server have same timestamp
            val bookDao: BookDao = mock()
            val contributorDao: ContributorDao = mock()
            val seriesDao: SeriesDao = mock()
            val detector = ConflictDetector(bookDao, contributorDao, seriesDao)

            val serverBook = createBookEntity("book-1", SyncState.SYNCED, lastModified = 2000L, updatedAt = 2000L)
            val localBook = createBookEntity("book-1", SyncState.NOT_SYNCED, lastModified = 2000L)

            everySuspend { bookDao.getById(BookId("book-1")) } returns localBook

            // When
            val conflicts = detector.detectBookConflicts(listOf(serverBook))

            // Then - no conflict: local (2000) >= server (2000)
            assertTrue(conflicts.isEmpty())
        }

    @Test
    fun `detectBookConflicts handles multiple books with mixed results`() =
        runTest {
            // Given - multiple books with different scenarios
            val bookDao: BookDao = mock()
            val contributorDao: ContributorDao = mock()
            val seriesDao: SeriesDao = mock()
            val detector = ConflictDetector(bookDao, contributorDao, seriesDao)

            val serverBooks =
                listOf(
                    createBookEntity("book-1", SyncState.SYNCED, lastModified = 5000L, updatedAt = 5000L), // Conflict
                    createBookEntity("book-2", SyncState.SYNCED, lastModified = 1000L, updatedAt = 1000L), // No conflict (local newer)
                    createBookEntity("book-3", SyncState.SYNCED, lastModified = 3000L, updatedAt = 3000L), // No conflict (synced)
                    createBookEntity("book-4", SyncState.SYNCED, lastModified = 4000L, updatedAt = 4000L), // No conflict (not in local)
                )

            everySuspend { bookDao.getById(BookId("book-1")) } returns
                createBookEntity("book-1", SyncState.NOT_SYNCED, lastModified = 2000L) // Conflict: server 5000 > local 2000

            everySuspend { bookDao.getById(BookId("book-2")) } returns
                createBookEntity("book-2", SyncState.NOT_SYNCED, lastModified = 3000L) // No conflict: local 3000 > server 1000

            everySuspend { bookDao.getById(BookId("book-3")) } returns
                createBookEntity("book-3", SyncState.SYNCED, lastModified = 2000L) // No conflict: already synced

            everySuspend { bookDao.getById(BookId("book-4")) } returns null // No conflict: not in local

            // When
            val conflicts = detector.detectBookConflicts(serverBooks)

            // Then - only book-1 has conflict
            assertEquals(1, conflicts.size)
            assertEquals(BookId("book-1"), conflicts[0].bookId)
        }

    // ========== shouldPreserveLocalChanges Tests ==========

    @Test
    fun `shouldPreserveLocalChanges returns false when book not in local DB`() =
        runTest {
            // Given
            val bookDao: BookDao = mock()
            val contributorDao: ContributorDao = mock()
            val seriesDao: SeriesDao = mock()
            val detector = ConflictDetector(bookDao, contributorDao, seriesDao)

            val serverBook = createBookEntity("book-1", SyncState.SYNCED, lastModified = 2000L)
            everySuspend { bookDao.getById(BookId("book-1")) } returns null

            // When
            val shouldPreserve = detector.shouldPreserveLocalChanges(serverBook)

            // Then - can't preserve what doesn't exist
            assertFalse(shouldPreserve)
        }

    @Test
    fun `shouldPreserveLocalChanges returns false when local is synced`() =
        runTest {
            // Given - local exists but has no unsynced changes
            val bookDao: BookDao = mock()
            val contributorDao: ContributorDao = mock()
            val seriesDao: SeriesDao = mock()
            val detector = ConflictDetector(bookDao, contributorDao, seriesDao)

            val serverBook = createBookEntity("book-1", SyncState.SYNCED, lastModified = 1000L, updatedAt = 1000L)
            val localBook = createBookEntity("book-1", SyncState.SYNCED, lastModified = 2000L)

            everySuspend { bookDao.getById(BookId("book-1")) } returns localBook

            // When
            val shouldPreserve = detector.shouldPreserveLocalChanges(serverBook)

            // Then - no local changes to preserve
            assertFalse(shouldPreserve)
        }

    @Test
    fun `shouldPreserveLocalChanges returns true when local unsynced is newer`() =
        runTest {
            // Given - local has newer unsynced changes
            val bookDao: BookDao = mock()
            val contributorDao: ContributorDao = mock()
            val seriesDao: SeriesDao = mock()
            val detector = ConflictDetector(bookDao, contributorDao, seriesDao)

            val serverBook = createBookEntity("book-1", SyncState.SYNCED, lastModified = 2000L, updatedAt = 2000L)
            val localBook = createBookEntity("book-1", SyncState.NOT_SYNCED, lastModified = 3000L)

            everySuspend { bookDao.getById(BookId("book-1")) } returns localBook

            // When
            val shouldPreserve = detector.shouldPreserveLocalChanges(serverBook)

            // Then - local changes should be preserved
            assertTrue(shouldPreserve)
        }

    @Test
    fun `shouldPreserveLocalChanges returns true when timestamps equal`() =
        runTest {
            // Given - same timestamp, but local has unsynced changes
            val bookDao: BookDao = mock()
            val contributorDao: ContributorDao = mock()
            val seriesDao: SeriesDao = mock()
            val detector = ConflictDetector(bookDao, contributorDao, seriesDao)

            val serverBook = createBookEntity("book-1", SyncState.SYNCED, lastModified = 2000L, updatedAt = 2000L)
            val localBook = createBookEntity("book-1", SyncState.NOT_SYNCED, lastModified = 2000L)

            everySuspend { bookDao.getById(BookId("book-1")) } returns localBook

            // When
            val shouldPreserve = detector.shouldPreserveLocalChanges(serverBook)

            // Then - preserve local (tie goes to local)
            assertTrue(shouldPreserve)
        }

    @Test
    fun `shouldPreserveLocalChanges returns false when server is newer`() =
        runTest {
            // Given - server has newer changes
            val bookDao: BookDao = mock()
            val contributorDao: ContributorDao = mock()
            val seriesDao: SeriesDao = mock()
            val detector = ConflictDetector(bookDao, contributorDao, seriesDao)

            val serverBook = createBookEntity("book-1", SyncState.SYNCED, lastModified = 3000L, updatedAt = 3000L)
            val localBook = createBookEntity("book-1", SyncState.NOT_SYNCED, lastModified = 2000L)

            everySuspend { bookDao.getById(BookId("book-1")) } returns localBook

            // When
            val shouldPreserve = detector.shouldPreserveLocalChanges(serverBook)

            // Then - server wins, don't preserve local
            assertFalse(shouldPreserve)
        }

    // ========== checkPushConflict Tests ==========

    private fun createPendingOperation(
        id: String = "op-1",
        operationType: OperationType = OperationType.BOOK_UPDATE,
        entityType: EntityType? = EntityType.BOOK,
        entityId: String? = "book-1",
        createdAt: Long = 2000L,
    ): PendingOperationEntity =
        PendingOperationEntity(
            id = id,
            operationType = operationType,
            entityType = entityType,
            entityId = entityId,
            payload = "{}",
            batchKey = null,
            status = OperationStatus.PENDING,
            createdAt = createdAt,
            updatedAt = createdAt,
            attemptCount = 0,
            lastError = null,
        )

    private fun createContributorEntity(
        id: String,
        updatedAt: Long,
    ): ContributorEntity =
        ContributorEntity(
            id =
                com.calypsan.listenup.client.core
                    .ContributorId(id),
            name = "Contributor $id",
            description = null,
            imagePath = null,
            syncState = SyncState.SYNCED,
            lastModified = Timestamp(updatedAt),
            serverVersion = Timestamp(updatedAt),
            createdAt = Timestamp(1000L),
            updatedAt = Timestamp(updatedAt),
        )

    private fun createSeriesEntity(
        id: String,
        updatedAt: Long,
    ): SeriesEntity =
        SeriesEntity(
            id =
                com.calypsan.listenup.client.core
                    .SeriesId(id),
            name = "Series $id",
            description = null,
            syncState = SyncState.SYNCED,
            lastModified = Timestamp(updatedAt),
            serverVersion = Timestamp(updatedAt),
            createdAt = Timestamp(1000L),
            updatedAt = Timestamp(updatedAt),
        )

    @Test
    fun `checkPushConflict returns null for non-entity operations`() =
        runTest {
            // Given - operation without entityId (e.g., listening event)
            val bookDao: BookDao = mock()
            val contributorDao: ContributorDao = mock()
            val seriesDao: SeriesDao = mock()
            val detector = ConflictDetector(bookDao, contributorDao, seriesDao)

            val operation =
                createPendingOperation(
                    entityType = null,
                    entityId = null,
                    operationType = OperationType.LISTENING_EVENT,
                )

            // When
            val conflict = detector.checkPushConflict(operation)

            // Then - No conflict for non-entity operations
            assertNull(conflict)
        }

    @Test
    fun `checkPushConflict returns null when entity not found`() =
        runTest {
            // Given - book doesn't exist in local DB
            val bookDao: BookDao = mock()
            val contributorDao: ContributorDao = mock()
            val seriesDao: SeriesDao = mock()
            val detector = ConflictDetector(bookDao, contributorDao, seriesDao)

            everySuspend { bookDao.getById(BookId("book-1")) } returns null

            val operation =
                createPendingOperation(
                    entityType = EntityType.BOOK,
                    entityId = "book-1",
                    createdAt = 2000L,
                )

            // When
            val conflict = detector.checkPushConflict(operation)

            // Then - No conflict (operation might fail on push, but not a conflict)
            assertNull(conflict)
        }

    @Test
    fun `checkPushConflict returns null when operation is newer than server`() =
        runTest {
            // Given - operation was created after server's last update
            val bookDao: BookDao = mock()
            val contributorDao: ContributorDao = mock()
            val seriesDao: SeriesDao = mock()
            val detector = ConflictDetector(bookDao, contributorDao, seriesDao)

            val book = createBookEntity("book-1", SyncState.SYNCED, lastModified = 1000L, updatedAt = 1000L)
            everySuspend { bookDao.getById(BookId("book-1")) } returns book

            val operation =
                createPendingOperation(
                    entityType = EntityType.BOOK,
                    entityId = "book-1",
                    createdAt = 2000L, // Operation created after server update
                )

            // When
            val conflict = detector.checkPushConflict(operation)

            // Then - No conflict
            assertNull(conflict)
        }

    @Test
    fun `checkPushConflict returns conflict when server is newer than operation`() =
        runTest {
            // Given - server was updated after operation was created
            val bookDao: BookDao = mock()
            val contributorDao: ContributorDao = mock()
            val seriesDao: SeriesDao = mock()
            val detector = ConflictDetector(bookDao, contributorDao, seriesDao)

            val book = createBookEntity("book-1", SyncState.SYNCED, lastModified = 3000L, updatedAt = 3000L)
            everySuspend { bookDao.getById(BookId("book-1")) } returns book

            val operation =
                createPendingOperation(
                    entityType = EntityType.BOOK,
                    entityId = "book-1",
                    createdAt = 2000L, // Operation created before server update
                )

            // When
            val conflict = detector.checkPushConflict(operation)

            // Then - Conflict detected
            assertNotNull(conflict)
            assertEquals("op-1", conflict.operationId)
            assertEquals("Server has newer changes", conflict.reason)
        }

    @Test
    fun `checkPushConflict returns null when timestamps are equal`() =
        runTest {
            // Given - operation created at same time as server update
            val bookDao: BookDao = mock()
            val contributorDao: ContributorDao = mock()
            val seriesDao: SeriesDao = mock()
            val detector = ConflictDetector(bookDao, contributorDao, seriesDao)

            val book = createBookEntity("book-1", SyncState.SYNCED, lastModified = 2000L, updatedAt = 2000L)
            everySuspend { bookDao.getById(BookId("book-1")) } returns book

            val operation =
                createPendingOperation(
                    entityType = EntityType.BOOK,
                    entityId = "book-1",
                    createdAt = 2000L, // Same as server
                )

            // When
            val conflict = detector.checkPushConflict(operation)

            // Then - No conflict (tie goes to local)
            assertNull(conflict)
        }

    @Test
    fun `checkPushConflict works for contributor updates`() =
        runTest {
            // Given
            val bookDao: BookDao = mock()
            val contributorDao: ContributorDao = mock()
            val seriesDao: SeriesDao = mock()
            val detector = ConflictDetector(bookDao, contributorDao, seriesDao)

            val contributor = createContributorEntity("contrib-1", updatedAt = 3000L)
            everySuspend { contributorDao.getById("contrib-1") } returns contributor

            val operation =
                createPendingOperation(
                    operationType = OperationType.CONTRIBUTOR_UPDATE,
                    entityType = EntityType.CONTRIBUTOR,
                    entityId = "contrib-1",
                    createdAt = 2000L,
                )

            // When
            val conflict = detector.checkPushConflict(operation)

            // Then
            assertNotNull(conflict)
            assertEquals("op-1", conflict.operationId)
        }

    @Test
    fun `checkPushConflict works for series updates`() =
        runTest {
            // Given
            val bookDao: BookDao = mock()
            val contributorDao: ContributorDao = mock()
            val seriesDao: SeriesDao = mock()
            val detector = ConflictDetector(bookDao, contributorDao, seriesDao)

            val series = createSeriesEntity("series-1", updatedAt = 3000L)
            everySuspend { seriesDao.getById("series-1") } returns series

            val operation =
                createPendingOperation(
                    operationType = OperationType.SERIES_UPDATE,
                    entityType = EntityType.SERIES,
                    entityId = "series-1",
                    createdAt = 2000L,
                )

            // When
            val conflict = detector.checkPushConflict(operation)

            // Then
            assertNotNull(conflict)
            assertEquals("op-1", conflict.operationId)
        }

    @Test
    fun `checkPushConflict returns null for contributor when no conflict`() =
        runTest {
            // Given
            val bookDao: BookDao = mock()
            val contributorDao: ContributorDao = mock()
            val seriesDao: SeriesDao = mock()
            val detector = ConflictDetector(bookDao, contributorDao, seriesDao)

            val contributor = createContributorEntity("contrib-1", updatedAt = 1000L)
            everySuspend { contributorDao.getById("contrib-1") } returns contributor

            val operation =
                createPendingOperation(
                    operationType = OperationType.CONTRIBUTOR_UPDATE,
                    entityType = EntityType.CONTRIBUTOR,
                    entityId = "contrib-1",
                    createdAt = 2000L, // After server update
                )

            // When
            val conflict = detector.checkPushConflict(operation)

            // Then
            assertNull(conflict)
        }
}
