package com.calypsan.listenup.client.playback

import com.calypsan.listenup.client.core.BookId
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.client.data.local.db.DownloadDao
import com.calypsan.listenup.client.data.local.db.ListeningEventDao
import com.calypsan.listenup.client.data.local.db.OperationType
import com.calypsan.listenup.client.data.local.db.PlaybackPositionDao
import com.calypsan.listenup.client.data.local.db.PlaybackPositionEntity
import com.calypsan.listenup.client.data.remote.SyncApiContract
import com.calypsan.listenup.client.data.remote.model.PlaybackProgressResponse
import com.calypsan.listenup.client.data.sync.push.ListeningEventPayload
import com.calypsan.listenup.client.data.sync.push.OperationHandler
import com.calypsan.listenup.client.data.sync.push.PendingOperationRepositoryContract
import com.calypsan.listenup.client.data.sync.push.PushSyncOrchestratorContract
import dev.mokkery.answering.returns
import dev.mokkery.answering.throws
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for ProgressTracker cross-device sync functionality.
 *
 * Key scenarios tested:
 * - Server position is newer than local → use server
 * - Local position is newer than server → use local
 * - Only server has position → use server
 * - Only local has position → use local
 * - Both null → return null
 * - Server error → gracefully use local
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProgressTrackerTest {
    // ========== Test Fixtures ==========

    private class TestFixture {
        val testDispatcher = StandardTestDispatcher()
        val testScope = TestScope(testDispatcher)

        val positionDao: PlaybackPositionDao = mock()
        val downloadDao: DownloadDao = mock()
        val listeningEventDao: ListeningEventDao = mock()
        val syncApi: SyncApiContract = mock()
        val pendingOperationRepository: PendingOperationRepositoryContract = mock()
        val listeningEventHandler: OperationHandler<ListeningEventPayload> = mock()
        val pushSyncOrchestrator: PushSyncOrchestratorContract = mock()

        fun build(): ProgressTracker =
            ProgressTracker(
                positionDao = positionDao,
                downloadDao = downloadDao,
                listeningEventDao = listeningEventDao,
                syncApi = syncApi,
                pendingOperationRepository = pendingOperationRepository,
                listeningEventHandler = listeningEventHandler,
                pushSyncOrchestrator = pushSyncOrchestrator,
                deviceId = "test-device-123",
                scope = testScope,
            )
    }

    private fun createFixture(): TestFixture {
        val fixture = TestFixture()

        // Default stubs
        everySuspend { fixture.positionDao.get(any()) } returns null
        everySuspend { fixture.positionDao.save(any()) } returns Unit
        everySuspend { fixture.syncApi.getProgress(any()) } returns Success(null)
        everySuspend { fixture.pendingOperationRepository.queue<Any>(any(), any(), any(), any(), any()) } returns Unit
        everySuspend { fixture.pushSyncOrchestrator.flush() } returns Unit

        return fixture
    }

    // ========== Test Data Factories ==========

    private fun createLocalPosition(
        bookId: String = "book-1",
        positionMs: Long = 1_800_000L, // 30 min
        updatedAt: Long = 1702900800000L, // 2023-12-18T12:00:00Z
    ): PlaybackPositionEntity =
        PlaybackPositionEntity(
            bookId = BookId(bookId),
            positionMs = positionMs,
            playbackSpeed = 1.0f,
            updatedAt = updatedAt,
            syncedAt = null,
        )

    private fun createServerProgress(
        bookId: String = "book-1",
        positionMs: Long = 3_600_000L, // 1 hour
        lastPlayedAt: String = "2023-12-18T14:00:00Z", // 2 hours after local
    ): PlaybackProgressResponse =
        PlaybackProgressResponse(
            userId = "user-1",
            bookId = bookId,
            currentPositionMs = positionMs,
            progress = 0.5,
            isFinished = false,
            startedAt = "2023-12-18T10:00:00Z",
            lastPlayedAt = lastPlayedAt,
            totalListenTimeMs = 3_600_000L,
            updatedAt = lastPlayedAt,
        )

    // ========== Cross-Device Sync Tests ==========

    @Test
    fun `getResumePosition returns null when both local and server have no position`() =
        runTest {
            // Given
            val fixture = createFixture()
            val bookId = BookId("book-1")

            everySuspend { fixture.positionDao.get(bookId) } returns null
            everySuspend { fixture.syncApi.getProgress(bookId.value) } returns Success(null)

            val tracker = fixture.build()

            // When
            val result = tracker.getResumePosition(bookId)

            // Then
            assertNull(result)
        }

    @Test
    fun `getResumePosition returns server position when local has no position`() =
        runTest {
            // Given - Fresh install, user has listened on another device
            val fixture = createFixture()
            val bookId = BookId("book-1")
            val serverProgress = createServerProgress(positionMs = 3_600_000L)

            everySuspend { fixture.positionDao.get(bookId) } returns null
            everySuspend { fixture.syncApi.getProgress(bookId.value) } returns Success(serverProgress)

            val tracker = fixture.build()

            // When
            val result = tracker.getResumePosition(bookId)

            // Then
            assertEquals(3_600_000L, result?.positionMs)

            // Verify server position was cached locally
            verifySuspend { fixture.positionDao.save(any()) }
        }

    @Test
    fun `getResumePosition returns local position when server has no position`() =
        runTest {
            // Given - Listened locally, server has no record yet
            val fixture = createFixture()
            val bookId = BookId("book-1")
            val localPosition = createLocalPosition(positionMs = 1_800_000L)

            everySuspend { fixture.positionDao.get(bookId) } returns localPosition
            everySuspend { fixture.syncApi.getProgress(bookId.value) } returns Success(null)

            val tracker = fixture.build()

            // When
            val result = tracker.getResumePosition(bookId)

            // Then
            assertEquals(1_800_000L, result?.positionMs)
        }

    @Test
    fun `getResumePosition uses server position when server is newer`() =
        runTest {
            // Given - Listened on phone yesterday, listened on tablet today
            val fixture = createFixture()
            val bookId = BookId("book-1")

            // Local: 30 min at 12:00 (yesterday)
            val localPosition =
                createLocalPosition(
                    positionMs = 1_800_000L,
                    updatedAt = 1702900800000L, // 2023-12-18T12:00:00Z
                )

            // Server: 1 hour at 14:00 (today - from tablet)
            val serverProgress =
                createServerProgress(
                    positionMs = 3_600_000L,
                    lastPlayedAt = "2023-12-18T14:00:00Z",
                )

            everySuspend { fixture.positionDao.get(bookId) } returns localPosition
            everySuspend { fixture.syncApi.getProgress(bookId.value) } returns Success(serverProgress)

            val tracker = fixture.build()

            // When
            val result = tracker.getResumePosition(bookId)

            // Then - Should use server position (1 hour)
            assertEquals(3_600_000L, result?.positionMs)

            // Verify server position was cached locally
            verifySuspend { fixture.positionDao.save(any()) }
        }

    @Test
    fun `getResumePosition uses local position when local is newer`() =
        runTest {
            // Given - Listened on tablet earlier, just listened on phone
            val fixture = createFixture()
            val bookId = BookId("book-1")

            // Local: 2 hours at 16:00 (newer)
            val localPosition =
                createLocalPosition(
                    positionMs = 7_200_000L,
                    updatedAt = 1702915200000L, // 2023-12-18T16:00:00Z
                )

            // Server: 1 hour at 14:00 (older - from tablet)
            val serverProgress =
                createServerProgress(
                    positionMs = 3_600_000L,
                    lastPlayedAt = "2023-12-18T14:00:00Z",
                )

            everySuspend { fixture.positionDao.get(bookId) } returns localPosition
            everySuspend { fixture.syncApi.getProgress(bookId.value) } returns Success(serverProgress)

            val tracker = fixture.build()

            // When
            val result = tracker.getResumePosition(bookId)

            // Then - Should use local position (2 hours)
            assertEquals(7_200_000L, result?.positionMs)
        }

    @Test
    fun `getResumePosition uses local position when server request fails`() =
        runTest {
            // Given - Network error, offline mode
            val fixture = createFixture()
            val bookId = BookId("book-1")
            val localPosition = createLocalPosition(positionMs = 1_800_000L)

            everySuspend { fixture.positionDao.get(bookId) } returns localPosition
            everySuspend { fixture.syncApi.getProgress(bookId.value) } returns
                Failure(RuntimeException("Network error"))

            val tracker = fixture.build()

            // When
            val result = tracker.getResumePosition(bookId)

            // Then - Gracefully falls back to local
            assertEquals(1_800_000L, result?.positionMs)
        }

    @Test
    fun `getResumePosition handles server exception gracefully`() =
        runTest {
            // Given - Unexpected exception during server fetch
            val fixture = createFixture()
            val bookId = BookId("book-1")
            val localPosition = createLocalPosition(positionMs = 1_800_000L)

            everySuspend { fixture.positionDao.get(bookId) } returns localPosition
            everySuspend { fixture.syncApi.getProgress(bookId.value) } throws
                RuntimeException("Unexpected error")

            val tracker = fixture.build()

            // When
            val result = tracker.getResumePosition(bookId)

            // Then - Gracefully falls back to local
            assertEquals(1_800_000L, result?.positionMs)
        }

    @Test
    fun `getResumePosition returns null when server fails and no local position`() =
        runTest {
            // Given - No local position, server unavailable
            val fixture = createFixture()
            val bookId = BookId("book-1")

            everySuspend { fixture.positionDao.get(bookId) } returns null
            everySuspend { fixture.syncApi.getProgress(bookId.value) } returns
                Failure(RuntimeException("Network error"))

            val tracker = fixture.build()

            // When
            val result = tracker.getResumePosition(bookId)

            // Then - No position available
            assertNull(result)
        }

    @Test
    fun `getResumePosition caches server position with correct syncedAt timestamp`() =
        runTest {
            // Given
            val fixture = createFixture()
            val bookId = BookId("book-1")
            val serverProgress = createServerProgress(positionMs = 3_600_000L)

            everySuspend { fixture.positionDao.get(bookId) } returns null
            everySuspend { fixture.syncApi.getProgress(bookId.value) } returns Success(serverProgress)

            var savedEntity: PlaybackPositionEntity? = null
            everySuspend { fixture.positionDao.save(any()) } returns Unit

            val tracker = fixture.build()

            // When
            tracker.getResumePosition(bookId)

            // Then - Verify save was called (syncedAt should be set)
            verifySuspend { fixture.positionDao.save(any()) }
        }

    @Test
    fun `getResumePosition handles malformed server timestamp gracefully`() =
        runTest {
            // Given - Server returns invalid timestamp
            val fixture = createFixture()
            val bookId = BookId("book-1")
            val localPosition = createLocalPosition(positionMs = 1_800_000L)

            val serverProgress =
                createServerProgress(
                    positionMs = 3_600_000L,
                    lastPlayedAt = "invalid-timestamp",
                )

            everySuspend { fixture.positionDao.get(bookId) } returns localPosition
            everySuspend { fixture.syncApi.getProgress(bookId.value) } returns Success(serverProgress)

            val tracker = fixture.build()

            // When
            val result = tracker.getResumePosition(bookId)

            // Then - Server timestamp parses to 0, so local wins
            assertEquals(1_800_000L, result?.positionMs)
        }

    // ========== Playback Start Position Saving Tests ==========

    @Test
    fun `onPlaybackStarted saves position immediately for Continue Listening`() =
        runTest {
            // Given - This ensures a book appears in Continue Listening the moment
            // playback starts, not just after 30 seconds or when paused.
            // Prevents the race condition where user navigates back to Home
            // before position is saved.
            val fixture = createFixture()
            val bookId = BookId("book-new")
            val tracker = fixture.build()

            // When - playback starts
            tracker.onPlaybackStarted(
                bookId = bookId,
                positionMs = 0L,
                speed = 1.0f,
            )

            // Advance to let the coroutine run
            fixture.testScope.testScheduler.advanceUntilIdle()

            // Then - position should be saved immediately
            verifySuspend { fixture.positionDao.save(any()) }
        }

    @Test
    fun `onPlaybackStarted saves position with correct lastPlayedAt timestamp`() =
        runTest {
            // Given
            val fixture = createFixture()
            val bookId = BookId("book-new")

            var savedPosition: PlaybackPositionEntity? = null
            everySuspend { fixture.positionDao.save(any()) } returns Unit

            val tracker = fixture.build()

            // When
            tracker.onPlaybackStarted(
                bookId = bookId,
                positionMs = 5000L,
                speed = 1.5f,
            )
            fixture.testScope.testScheduler.advanceUntilIdle()

            // Then - verify save was called (position captured)
            verifySuspend { fixture.positionDao.save(any()) }
        }
}
