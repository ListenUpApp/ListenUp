package com.calypsan.listenup.client.playback

import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.core.BookId
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.client.data.local.db.EntityType
import com.calypsan.listenup.client.data.local.db.OperationType
import com.calypsan.listenup.client.data.local.db.PlaybackPositionEntity
import com.calypsan.listenup.client.data.remote.SyncApiContract
import com.calypsan.listenup.client.data.remote.model.PlaybackProgressResponse
import com.calypsan.listenup.client.data.sync.push.EndPlaybackSessionHandler
import com.calypsan.listenup.client.data.sync.push.EndPlaybackSessionPayload
import com.calypsan.listenup.client.data.sync.push.PendingOperationRepositoryContract
import com.calypsan.listenup.client.data.sync.push.PushSyncOrchestratorContract
import com.calypsan.listenup.client.domain.repository.DownloadRepository
import com.calypsan.listenup.client.domain.repository.ListeningEventRepository
import com.calypsan.listenup.client.domain.repository.PlaybackPositionRepository
import com.calypsan.listenup.client.domain.repository.PlaybackUpdate
import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.answering.throws
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.matcher.matches
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode
import dev.mokkery.verifySuspend
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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

        val downloadRepository: DownloadRepository = mock()
        val listeningEventRepository: ListeningEventRepository = mock()
        val syncApi: SyncApiContract = mock()
        val pushSyncOrchestrator: PushSyncOrchestratorContract = mock()
        val positionRepository: PlaybackPositionRepository = mock()
        val pendingOperationRepository: PendingOperationRepositoryContract =
            mock(MockMode.autoUnit)
        val endPlaybackSessionHandler: EndPlaybackSessionHandler =
            EndPlaybackSessionHandler(syncApi)

        fun build(): ProgressTracker =
            ProgressTracker(
                downloadRepository = downloadRepository,
                listeningEventRepository = listeningEventRepository,
                syncApi = syncApi,
                pushSyncOrchestrator = pushSyncOrchestrator,
                positionRepository = positionRepository,
                pendingOperationRepository = pendingOperationRepository,
                endPlaybackSessionHandler = endPlaybackSessionHandler,
                scope = testScope,
            )
    }

    private fun createFixture(): TestFixture {
        val fixture = TestFixture()

        // Default stubs
        everySuspend { fixture.syncApi.getProgress(any()) } returns Success(null)
        everySuspend { fixture.listeningEventRepository.queueListeningEvent(any(), any(), any(), any(), any(), any()) } returns AppResult.Success(Unit)
        everySuspend { fixture.pushSyncOrchestrator.flush() } returns Unit
        everySuspend { fixture.downloadRepository.deleteForBook(any()) } returns Unit
        everySuspend { fixture.positionRepository.savePlaybackState(any(), any()) } returns AppResult.Success(Unit)
        everySuspend { fixture.positionRepository.getEntity(any<BookId>()) } returns AppResult.Success(null)

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

            everySuspend { fixture.positionRepository.getEntity(bookId) } returns AppResult.Success(null)
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
            val cachedEntity = createLocalPosition(positionMs = 3_600_000L)

            everySuspend { fixture.positionRepository.getEntity(bookId) } returns AppResult.Success(null)
            everySuspend { fixture.syncApi.getProgress(bookId.value) } returns Success(serverProgress)
            // After the CrossDeviceSync write, the read-back returns the cached entity
            everySuspend { fixture.positionRepository.getEntity(bookId) } returns AppResult.Success(cachedEntity)

            val tracker = fixture.build()

            // When
            val result = tracker.getResumePosition(bookId)

            // Then — server position returned via read-back
            assertEquals(3_600_000L, result?.positionMs)

            // Verify server position was written via CrossDeviceSync
            verifySuspend(VerifyMode.atLeast(1)) {
                fixture.positionRepository.savePlaybackState(
                    bookId,
                    matches<PlaybackUpdate>({ "CrossDeviceSync" }) { it is PlaybackUpdate.CrossDeviceSync },
                )
            }
        }

    @Test
    fun `getResumePosition returns local position when server has no position`() =
        runTest {
            // Given - Listened locally, server has no record yet
            val fixture = createFixture()
            val bookId = BookId("book-1")
            val localPosition = createLocalPosition(positionMs = 1_800_000L)

            everySuspend { fixture.positionRepository.getEntity(bookId) } returns AppResult.Success(localPosition)
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

            val mergedEntity = createLocalPosition(positionMs = 3_600_000L)

            everySuspend { fixture.positionRepository.getEntity(bookId) } returns AppResult.Success(localPosition)
            everySuspend { fixture.syncApi.getProgress(bookId.value) } returns Success(serverProgress)
            // After the CrossDeviceSync write, the read-back returns the merged entity
            everySuspend { fixture.positionRepository.getEntity(bookId) } returns AppResult.Success(mergedEntity)

            val tracker = fixture.build()

            // When
            val result = tracker.getResumePosition(bookId)

            // Then - Should use server position (1 hour)
            assertEquals(3_600_000L, result?.positionMs)

            // Verify server position was written via CrossDeviceSync
            verifySuspend(VerifyMode.atLeast(1)) {
                fixture.positionRepository.savePlaybackState(
                    bookId,
                    matches<PlaybackUpdate>({ "CrossDeviceSync" }) { it is PlaybackUpdate.CrossDeviceSync },
                )
            }
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

            everySuspend { fixture.positionRepository.getEntity(bookId) } returns AppResult.Success(localPosition)
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

            everySuspend { fixture.positionRepository.getEntity(bookId) } returns AppResult.Success(localPosition)
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

            everySuspend { fixture.positionRepository.getEntity(bookId) } returns AppResult.Success(localPosition)
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

            everySuspend { fixture.positionRepository.getEntity(bookId) } returns AppResult.Success(null)
            everySuspend { fixture.syncApi.getProgress(bookId.value) } returns
                Failure(RuntimeException("Network error"))

            val tracker = fixture.build()

            // When
            val result = tracker.getResumePosition(bookId)

            // Then - No position available
            assertNull(result)
        }

    @Test
    fun `getResumePosition caches server position via CrossDeviceSync`() =
        runTest {
            // Given
            val fixture = createFixture()
            val bookId = BookId("book-1")
            val serverProgress = createServerProgress(positionMs = 3_600_000L)

            everySuspend { fixture.positionRepository.getEntity(bookId) } returns AppResult.Success(null)
            everySuspend { fixture.syncApi.getProgress(bookId.value) } returns Success(serverProgress)

            val tracker = fixture.build()

            // When
            tracker.getResumePosition(bookId)

            // Then - Verify save was called via CrossDeviceSync (syncedAt is set by handler)
            verifySuspend(VerifyMode.atLeast(1)) {
                fixture.positionRepository.savePlaybackState(
                    bookId,
                    matches<PlaybackUpdate>({ "CrossDeviceSync" }) { it is PlaybackUpdate.CrossDeviceSync },
                )
            }
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

            everySuspend { fixture.positionRepository.getEntity(bookId) } returns AppResult.Success(localPosition)
            everySuspend { fixture.syncApi.getProgress(bookId.value) } returns Success(serverProgress)

            val tracker = fixture.build()

            // When
            val result = tracker.getResumePosition(bookId)

            // Then - Server timestamp parses to 0, so local wins
            assertEquals(1_800_000L, result?.positionMs)
        }

    // ========== mergePositions CrossDeviceSync Tests ==========

    @Test
    fun `mergePositions writes via CrossDeviceSync when server is only progress`() =
        runTest {
            // Given - local null, server has progress (fresh install cross-device scenario)
            val fixture = createFixture()
            val bookId = BookId("book-1")
            val serverProgress = createServerProgress(positionMs = 3_600_000L)
            val cachedEntity = createLocalPosition(positionMs = 3_600_000L)

            everySuspend { fixture.positionRepository.getEntity(bookId) } returns AppResult.Success(cachedEntity)
            everySuspend { fixture.syncApi.getProgress(bookId.value) } returns Success(serverProgress)

            val tracker = fixture.build()

            // When
            tracker.getResumePosition(bookId)

            // Then - CrossDeviceSync write must happen
            verifySuspend(VerifyMode.atLeast(1)) {
                fixture.positionRepository.savePlaybackState(
                    bookId,
                    matches<PlaybackUpdate>({ "CrossDeviceSync" }) { it is PlaybackUpdate.CrossDeviceSync },
                )
            }
            // And a read-back via getEntity must happen
            verifySuspend(VerifyMode.atLeast(1)) {
                fixture.positionRepository.getEntity(bookId)
            }
        }

    @Test
    fun `mergePositions writes via CrossDeviceSync when server is newer`() =
        runTest {
            // Given - local exists at 12:00, server is newer at 14:00
            val fixture = createFixture()
            val bookId = BookId("book-1")
            val localPosition =
                createLocalPosition(
                    positionMs = 1_800_000L,
                    updatedAt = 1702900800000L, // 2023-12-18T12:00:00Z
                )
            val serverProgress =
                createServerProgress(
                    positionMs = 3_600_000L,
                    lastPlayedAt = "2023-12-18T14:00:00Z",
                )
            val mergedEntity = createLocalPosition(positionMs = 3_600_000L)

            everySuspend { fixture.positionRepository.getEntity(bookId) } returns AppResult.Success(localPosition)
            everySuspend { fixture.syncApi.getProgress(bookId.value) } returns Success(serverProgress)
            everySuspend { fixture.positionRepository.getEntity(bookId) } returns AppResult.Success(mergedEntity)

            val tracker = fixture.build()

            // When
            tracker.getResumePosition(bookId)

            // Then - CrossDeviceSync write must happen
            verifySuspend(VerifyMode.atLeast(1)) {
                fixture.positionRepository.savePlaybackState(
                    bookId,
                    matches<PlaybackUpdate>({ "CrossDeviceSync" }) { it is PlaybackUpdate.CrossDeviceSync },
                )
            }
            // And a read-back via getEntity must happen after the write
            verifySuspend(VerifyMode.atLeast(1)) {
                fixture.positionRepository.getEntity(bookId)
            }
        }

    @Test
    fun `mergePositions returns synthesized entity when read-back fails after CrossDeviceSync save`() =
        runTest {
            // Given - local null, server has progress; save succeeds but read-back returns Failure
            val fixture = createFixture()
            val bookId = BookId("book-1")
            val serverProgress = createServerProgress(positionMs = 3_600_000L)

            everySuspend { fixture.positionRepository.getEntity(bookId) } returns
                AppResult.Failure(TransportError.DataMalformed(detail = "read-back failure"))
            everySuspend { fixture.syncApi.getProgress(bookId.value) } returns Success(serverProgress)
            everySuspend { fixture.positionRepository.savePlaybackState(any(), any()) } returns AppResult.Success(Unit)

            val tracker = fixture.build()

            // When - local is null (getEntity fails), server provides position → server-only-branch
            val result = tracker.getResumePosition(bookId)

            // Then - fallback synthesizes a non-null result from server.toEntity()
            assertNotNull(result)
            assertEquals(3_600_000L, result.positionMs)
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

            // Then - position should be saved immediately via repository seam (PlaybackStarted
            // so the handler can insert a new row when none exists)
            verifySuspend {
                fixture.positionRepository.savePlaybackState(
                    bookId,
                    matches<PlaybackUpdate>({ "PlaybackStarted(positionMs=0, speed=1.0)" }) {
                        it is PlaybackUpdate.PlaybackStarted && it.positionMs == 0L && it.speed == 1.0f
                    },
                )
            }
        }

    @Test
    fun `onPlaybackStarted saves position with correct lastPlayedAt timestamp`() =
        runTest {
            // Given
            val fixture = createFixture()
            val bookId = BookId("book-new")
            val tracker = fixture.build()

            // When
            tracker.onPlaybackStarted(
                bookId = bookId,
                positionMs = 5000L,
                speed = 1.5f,
            )
            fixture.testScope.testScheduler.advanceUntilIdle()

            // Then - verify save was called via repository seam with correct args
            verifySuspend {
                fixture.positionRepository.savePlaybackState(
                    bookId,
                    matches<PlaybackUpdate>({ "PlaybackStarted(positionMs=5000, speed=1.5)" }) {
                        it is PlaybackUpdate.PlaybackStarted && it.positionMs == 5000L && it.speed == 1.5f
                    },
                )
            }
        }

    // ========== onBookFinished Tests ==========

    @Test
    fun `onBookFinished routes download-delete through downloadRepository not downloadDao`() =
        runTest {
            // Verifies drift #10: DAO writes in /playback/ routed through repository.
            // downloadRepository.deleteForBook is the correct domain-layer entry point.
            val fixture = createFixture()
            val bookId = BookId("book-finished")
            everySuspend { fixture.positionRepository.markComplete(any(), any(), any()) } returns AppResult.Success(Unit)

            val tracker = fixture.build()

            tracker.onBookFinished(bookId = bookId, finalPositionMs = 100_000L)
            fixture.testScope.testScheduler.advanceUntilIdle()

            verifySuspend { fixture.downloadRepository.deleteForBook(bookId.value) }
        }

    // ========== clearProgress Tests ==========

    @Test
    fun `clearProgress delegates to positionRepository not positionDao`() =
        runTest {
            // Verifies Finding 07 Rule 5: DAO writes in /playback/ routed through repository.
            // positionRepository.delete is the correct domain-layer entry point.
            val fixture = createFixture()
            val bookId = BookId("book-clear")
            everySuspend { fixture.positionRepository.delete(bookId) } returns AppResult.Success(Unit)

            val tracker = fixture.build()

            tracker.clearProgress(bookId)

            verifySuspend { fixture.positionRepository.delete(bookId) }
        }

    @Test
    fun `clearProgress logs warning when delete returns Failure`() =
        runTest {
            val fixture = createFixture()
            val bookId = BookId("book-1")
            everySuspend { fixture.positionRepository.delete(bookId) } returns
                AppResult.Failure(TransportError.DataMalformed(detail = "delete failure"))

            // Should not throw — best-effort logging absorbs the failure.
            fixture.build().clearProgress(bookId)

            verifySuspend(VerifyMode.exactly(1)) { fixture.positionRepository.delete(bookId) }
        }

    // ========== write-path migration (Task 5) ==========

    @Test
    fun `onSpeedChanged routes through savePlaybackState with Speed variant`() =
        runTest {
            val fixture = createFixture()
            val bookId = BookId("book-1")
            val tracker = fixture.build()

            tracker.onSpeedChanged(bookId = bookId, positionMs = 1500L, newSpeed = 1.5f)
            fixture.testScope.testScheduler.advanceUntilIdle()

            verifySuspend(VerifyMode.atLeast(1)) {
                fixture.positionRepository.savePlaybackState(
                    bookId,
                    matches<PlaybackUpdate>({ "Speed(positionMs=1500, speed=1.5, custom=true)" }) {
                        it is PlaybackUpdate.Speed && it.positionMs == 1500L && it.speed == 1.5f && it.custom == true
                    },
                )
            }
        }

    @Test
    fun `onSpeedReset routes through savePlaybackState with SpeedReset variant`() =
        runTest {
            val fixture = createFixture()
            val bookId = BookId("book-1")
            val tracker = fixture.build()

            tracker.onSpeedReset(bookId = bookId, positionMs = 1500L, defaultSpeed = 1.0f)
            fixture.testScope.testScheduler.advanceUntilIdle()

            verifySuspend(VerifyMode.atLeast(1)) {
                fixture.positionRepository.savePlaybackState(
                    bookId,
                    matches<PlaybackUpdate>({ "SpeedReset(positionMs=1500, defaultSpeed=1.0)" }) {
                        it is PlaybackUpdate.SpeedReset && it.positionMs == 1500L && it.defaultSpeed == 1.0f
                    },
                )
            }
        }

    @Test
    fun `onPositionUpdate routes through savePlaybackState with PeriodicUpdate variant`() =
        runTest {
            val fixture = createFixture()
            val bookId = BookId("book-1")
            val tracker = fixture.build()

            tracker.onPositionUpdate(bookId = bookId, positionMs = 5000L, speed = 1.0f)
            fixture.testScope.testScheduler.advanceUntilIdle()

            verifySuspend(VerifyMode.atLeast(1)) {
                fixture.positionRepository.savePlaybackState(
                    bookId,
                    matches<PlaybackUpdate>({ "PeriodicUpdate(positionMs=5000, speed=1.0)" }) {
                        it is PlaybackUpdate.PeriodicUpdate && it.positionMs == 5000L && it.speed == 1.0f
                    },
                )
            }
        }

    @Test
    fun `onPlaybackStarted routes through savePlaybackState with PlaybackStarted variant`() =
        runTest {
            val fixture = createFixture()
            val bookId = BookId("book-1")
            val tracker = fixture.build()

            tracker.onPlaybackStarted(bookId, positionMs = 1000L, speed = 1.0f)
            fixture.testScope.testScheduler.advanceUntilIdle()

            verifySuspend(VerifyMode.atLeast(1)) {
                fixture.positionRepository.savePlaybackState(
                    bookId,
                    matches<PlaybackUpdate>({ "PlaybackStarted(positionMs=1000, speed=1.0)" }) {
                        it is PlaybackUpdate.PlaybackStarted && it.positionMs == 1000L && it.speed == 1.0f
                    },
                )
            }
        }

    // ========== END_PLAYBACK_SESSION outbox routing (Task 8) ==========

    @Test
    fun `onPlaybackPaused queues END_PLAYBACK_SESSION when totalDuration is at least 30s`() =
        runTest {
            val fixture = createFixture()
            val bookId = BookId("book-1")
            val tracker = fixture.build()

            tracker.onPlaybackStarted(bookId, positionMs = 1_000L, speed = 1.0f)
            tracker.onPlaybackPaused(bookId, positionMs = 35_000L, speed = 1.0f) // 34s after start
            fixture.testScope.testScheduler.advanceUntilIdle()

            verifySuspend(VerifyMode.exactly(1)) {
                fixture.pendingOperationRepository.queue<EndPlaybackSessionPayload>(
                    matches({ "END_PLAYBACK_SESSION" }) { it == OperationType.END_PLAYBACK_SESSION },
                    matches({ "EntityType.BOOK" }) { it == EntityType.BOOK },
                    matches({ "book-1" }) { it == "book-1" },
                    matches({ "payload(bookId=book-1, durationMs=34000)" }) {
                        it.bookId == "book-1" && it.durationMs == 34_000L
                    },
                    any(),
                )
            }
        }

    @Test
    fun `onBookFinished queues END_PLAYBACK_SESSION when totalDuration is at least 30s`() =
        runTest {
            val fixture = createFixture()
            val bookId = BookId("book-1")
            everySuspend { fixture.positionRepository.markComplete(any(), any(), any()) } returns AppResult.Success(Unit)
            val tracker = fixture.build()

            tracker.onPlaybackStarted(bookId, positionMs = 0L, speed = 1.0f)
            tracker.onBookFinished(bookId, finalPositionMs = 35_000L) // 35s after start
            fixture.testScope.testScheduler.advanceUntilIdle()

            verifySuspend(VerifyMode.exactly(1)) {
                fixture.pendingOperationRepository.queue<EndPlaybackSessionPayload>(
                    matches({ "END_PLAYBACK_SESSION" }) { it == OperationType.END_PLAYBACK_SESSION },
                    matches({ "EntityType.BOOK" }) { it == EntityType.BOOK },
                    matches({ "book-1" }) { it == "book-1" },
                    matches({ "payload(bookId=book-1, durationMs=35000)" }) {
                        it.bookId == "book-1" && it.durationMs == 35_000L
                    },
                    any(),
                )
            }
        }

    @Test
    fun `onPlaybackPaused does not queue END_PLAYBACK_SESSION when totalDuration is below 30s`() =
        runTest {
            val fixture = createFixture()
            val bookId = BookId("book-1")
            val tracker = fixture.build()

            tracker.onPlaybackStarted(bookId, positionMs = 1_000L, speed = 1.0f)
            tracker.onPlaybackPaused(bookId, positionMs = 10_000L, speed = 1.0f) // 9s after start
            fixture.testScope.testScheduler.advanceUntilIdle()

            verifySuspend(VerifyMode.exactly(0)) {
                fixture.pendingOperationRepository.queue<EndPlaybackSessionPayload>(
                    matches({ "END_PLAYBACK_SESSION" }) { it == OperationType.END_PLAYBACK_SESSION },
                    any(),
                    any(),
                    any(),
                    any(),
                )
            }
        }

    // ========== SessionState transition tests (Task 9) ==========

    @Test
    fun `onPlaybackStarted from Idle transitions to Active`() =
        runTest {
            val fixture = createFixture()
            val tracker = fixture.build()
            tracker.onPlaybackStarted(BookId("book-1"), positionMs = 1000L, speed = 1.0f)

            assertIs<SessionState.Active>(tracker.sessionState.value)
            val active = tracker.sessionState.value as SessionState.Active
            assertEquals(BookId("book-1"), active.bookId)
            assertEquals(1000L, active.chunkStartPositionMs)
            assertEquals(1000L, active.playbackStartPositionMs)
            assertEquals(1.0f, active.speed)
        }

    @Test
    fun `onPlaybackPaused from Active transitions to Paused preserving timestamps`() =
        runTest {
            val fixture = createFixture()
            val tracker = fixture.build()
            tracker.onPlaybackStarted(BookId("book-1"), positionMs = 1000L, speed = 1.0f)
            val activeState = tracker.sessionState.value as SessionState.Active

            tracker.onPlaybackPaused(BookId("book-1"), positionMs = 6000L, speed = 1.0f)

            val pausedState = tracker.sessionState.value as SessionState.Paused
            assertEquals(activeState.chunkStartPositionMs, pausedState.chunkStartPositionMs)
            assertEquals(activeState.chunkStartedAt, pausedState.chunkStartedAt)
            assertEquals(activeState.playbackStartPositionMs, pausedState.playbackStartPositionMs)
            assertEquals(activeState.playbackStartedAt, pausedState.playbackStartedAt)
            assertEquals(activeState.speed, pausedState.speed)
            assertEquals(BookId("book-1"), pausedState.bookId)
        }

    @Test
    fun `onPlaybackPaused from Idle is a no-op`() =
        runTest {
            val fixture = createFixture()
            val tracker = fixture.build()
            tracker.onPlaybackPaused(BookId("book-1"), positionMs = 1000L, speed = 1.0f)
            assertEquals(SessionState.Idle, tracker.sessionState.value)
        }

    @Test
    fun `onPlaybackPaused with mismatched bookId is a no-op`() =
        runTest {
            val fixture = createFixture()
            val tracker = fixture.build()
            tracker.onPlaybackStarted(BookId("book-1"), positionMs = 1000L, speed = 1.0f)
            tracker.onPlaybackPaused(BookId("book-2"), positionMs = 2000L, speed = 1.0f)
            assertIs<SessionState.Active>(tracker.sessionState.value) // still Active for book-1
        }

    @Test
    fun `onSpeedChanged updates Active speed when bookId matches`() =
        runTest {
            val fixture = createFixture()
            val tracker = fixture.build()
            tracker.onPlaybackStarted(BookId("book-1"), positionMs = 1000L, speed = 1.0f)
            tracker.onSpeedChanged(BookId("book-1"), positionMs = 1500L, newSpeed = 1.5f)
            assertEquals(1.5f, (tracker.sessionState.value as SessionState.Active).speed)
        }

    @Test
    fun `onSpeedChanged in Idle does not change SessionState`() =
        runTest {
            val fixture = createFixture()
            val tracker = fixture.build()
            tracker.onSpeedChanged(BookId("book-1"), positionMs = 1500L, newSpeed = 1.5f)
            assertEquals(SessionState.Idle, tracker.sessionState.value)
        }

    @Test
    fun `onSpeedChanged with mismatched bookId does not change SessionState`() =
        runTest {
            val fixture = createFixture()
            val tracker = fixture.build()
            tracker.onPlaybackStarted(BookId("book-1"), positionMs = 1000L, speed = 1.0f)
            tracker.onSpeedChanged(BookId("book-2"), positionMs = 1500L, newSpeed = 1.5f)
            assertEquals(1.0f, (tracker.sessionState.value as SessionState.Active).speed) // unchanged
        }

    @Test
    fun `onSpeedChanged updates Paused speed when bookId matches`() =
        runTest {
            val fixture = createFixture()
            val tracker = fixture.build()
            tracker.onPlaybackStarted(BookId("book-1"), positionMs = 1000L, speed = 1.0f)
            tracker.onPlaybackPaused(BookId("book-1"), positionMs = 2000L, speed = 1.0f)
            tracker.onSpeedChanged(BookId("book-1"), positionMs = 2000L, newSpeed = 1.5f)
            assertEquals(1.5f, (tracker.sessionState.value as SessionState.Paused).speed)
        }

    @Test
    fun `onBookFinished transitions to Idle`() =
        runTest {
            val fixture = createFixture()
            everySuspend { fixture.positionRepository.markComplete(any(), any(), any()) } returns AppResult.Success(Unit)
            val tracker = fixture.build()
            tracker.onPlaybackStarted(BookId("book-1"), positionMs = 1000L, speed = 1.0f)
            tracker.onBookFinished(BookId("book-1"), finalPositionMs = 30000L)
            fixture.testScope.testScheduler.advanceUntilIdle()
            assertEquals(SessionState.Idle, tracker.sessionState.value)
        }

    @Test
    fun `onBookFinished consumes markComplete Failure without throwing`() =
        runTest {
            val fixture = createFixture()
            everySuspend {
                fixture.positionRepository.markComplete(any(), any(), any())
            } returns AppResult.Failure(TransportError.DataMalformed(detail = "write failure"))
            val tracker = fixture.build()

            tracker.onPlaybackStarted(BookId("book-1"), positionMs = 0L, speed = 1.0f)
            tracker.onBookFinished(BookId("book-1"), finalPositionMs = 35_000L)
            fixture.testScope.testScheduler.advanceUntilIdle()

            // No exception thrown. The Failure was logged and consumed.
            verifySuspend(VerifyMode.exactly(1)) {
                fixture.positionRepository.markComplete(BookId("book-1"), null, any())
            }
        }

    @Test
    fun `getCurrentSpeed returns 1_0 when Idle`() =
        runTest {
            val fixture = createFixture()
            val tracker = fixture.build()
            assertEquals(1.0f, tracker.getCurrentSpeed())
        }

    @Test
    fun `getCurrentSpeed returns Active speed`() =
        runTest {
            val fixture = createFixture()
            val tracker = fixture.build()
            tracker.onPlaybackStarted(BookId("book-1"), positionMs = 1000L, speed = 1.5f)
            assertEquals(1.5f, tracker.getCurrentSpeed())
        }

    @Test
    fun `getCurrentSpeed returns Paused speed`() =
        runTest {
            val fixture = createFixture()
            val tracker = fixture.build()
            tracker.onPlaybackStarted(BookId("book-1"), positionMs = 1000L, speed = 1.5f)
            tracker.onPlaybackPaused(BookId("book-1"), positionMs = 2000L, speed = 1.5f)
            assertEquals(1.5f, tracker.getCurrentSpeed())
        }

    @Test
    fun `onPositionUpdate advances chunk window when chunk duration above 10s`() =
        runTest {
            val fixture = createFixture()
            val tracker = fixture.build()
            tracker.onPlaybackStarted(BookId("book-1"), positionMs = 1000L, speed = 1.0f)
            val activeBefore = tracker.sessionState.value as SessionState.Active

            tracker.onPositionUpdate(BookId("book-1"), positionMs = 12_000L, speed = 1.0f) // 11s of chunk
            fixture.testScope.testScheduler.advanceUntilIdle() // let scope.launch complete

            val activeAfter = tracker.sessionState.value as SessionState.Active
            assertEquals(12_000L, activeAfter.chunkStartPositionMs) // chunk advanced
            assertEquals(activeBefore.playbackStartPositionMs, activeAfter.playbackStartPositionMs) // playback-start preserved
        }
}
