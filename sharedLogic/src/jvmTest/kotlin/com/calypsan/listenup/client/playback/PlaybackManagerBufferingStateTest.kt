package com.calypsan.listenup.client.playback

import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.ServerUrl
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.client.data.local.db.AudioFileEntity
import com.calypsan.listenup.client.data.local.db.BookEntity
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.device.DeviceContext
import com.calypsan.listenup.client.device.DeviceType
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.PlaybackPreferences
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.DownloadOutcome
import com.calypsan.listenup.client.download.DownloadService
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.fake.FakePlayer
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies the isBuffering and playbackState flows added in W7 Phase E2.1 Task 1,
 * and the AudioPlayer state observation wired in Task 4.
 *
 * These flows serve as the receiving end for platform-specific state pushes:
 * Android's MediaControllerHolder Player.Listener and Desktop's AudioPlayer.state
 * observation in startPlayback.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackManagerBufferingStateTest {
    private val db: ListenUpDatabase = createInMemoryTestDatabase()

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun `setBuffering updates isBuffering flow`() =
        runTest {
            val sut = createPlaybackManager()

            assertEquals(false, sut.isBuffering.value)

            sut.setBuffering(true)
            assertEquals(true, sut.isBuffering.value)

            sut.setBuffering(false)
            assertEquals(false, sut.isBuffering.value)
        }

    @Test
    fun `setPlaybackState updates playbackState flow`() =
        runTest {
            val sut = createPlaybackManager()

            assertEquals(PlaybackState.Idle, sut.playbackState.value)

            sut.setPlaybackState(PlaybackState.Playing)
            assertEquals(PlaybackState.Playing, sut.playbackState.value)

            sut.setPlaybackState(PlaybackState.Buffering)
            assertEquals(PlaybackState.Buffering, sut.playbackState.value)
        }

    @Test
    fun `startPlayback subscribes to AudioPlayer state and forwards to PlaybackManager`() =
        runTest {
            seedBookAndAudioFiles()

            // Use coroutineContext + Job() so launched coroutines share the
            // TestCoroutineScheduler (and are advanced by advanceUntilIdle()) while the
            // Job is independent of the TestScope — allowing explicit cancellation at the
            // end without tearing down the test harness.
            val managerScope = CoroutineScope(coroutineContext + Job())
            val sut = createPlaybackManager(scope = managerScope)

            val player = FakePlayer()

            val prepareResult = sut.prepareForPlayback(BookId("book-1"))
            checkNotNull(prepareResult) { "prepareForPlayback must succeed" }
            sut.activateBook(BookId("book-1"))

            // startPlayback launches the observation coroutines on managerScope.
            sut.startPlayback(player = player, resumePositionMs = 0L, resumeSpeed = 1.0f)

            // FakePlayer.load() drives state → Buffering; play() drives state → Playing.
            // Drain pending coroutines so the collectors process the latest emission.
            advanceUntilIdle()

            // After play(), FakePlayer emits Playing — expect state forwarded.
            assertEquals(PlaybackState.Playing, sut.playbackState.value)
            assertFalse(sut.isBuffering.value, "Playing state must clear isBuffering")

            // Now drive the player to Buffering and verify forwarding.
            player.emitState(PlaybackState.Buffering)
            advanceUntilIdle()

            assertEquals(PlaybackState.Buffering, sut.playbackState.value)
            assertEquals(true, sut.isBuffering.value)

            // clearPlayback must cancel observations — further emissions must not propagate.
            sut.clearPlayback()
            player.emitState(PlaybackState.Playing)
            advanceUntilIdle()

            // clearPlayback resets to Idle regardless of what the player emits.
            assertEquals(PlaybackState.Idle, sut.playbackState.value)
            assertFalse(sut.isBuffering.value, "clearPlayback must reset isBuffering to false")

            // Cancel to stop the infinite collect coroutines and let runTest complete.
            managerScope.coroutineContext[Job]?.cancel()
        }

    @Test
    fun `playerObservationJob surfaces PlaybackState_Error as PlaybackError on playbackError flow`() =
        runTest {
            seedBookAndAudioFiles()

            val managerScope = CoroutineScope(coroutineContext + Job())
            val sut = createPlaybackManager(scope = managerScope)
            val player = FakePlayer()

            val prepareResult = sut.prepareForPlayback(BookId("book-1"))
            checkNotNull(prepareResult) { "prepareForPlayback must succeed" }
            sut.activateBook(BookId("book-1"))

            sut.startPlayback(player = player, resumePositionMs = 0L, resumeSpeed = 1.0f)
            advanceUntilIdle()

            assertNull(sut.playbackError.value)

            player.emitState(PlaybackState.Error(message = "GStreamer hosed", isRecoverable = false))
            advanceUntilIdle()

            val error = sut.playbackError.value
            assertNotNull(error)
            assertEquals("GStreamer hosed", error.message)
            assertEquals(false, error.isRecoverable)

            managerScope.coroutineContext[Job]?.cancel()
        }

    @Test
    fun `playerObservationJob clears playbackError on transition to Playing`() =
        runTest {
            seedBookAndAudioFiles()

            val managerScope = CoroutineScope(coroutineContext + Job())
            val sut = createPlaybackManager(scope = managerScope)
            val player = FakePlayer()

            val prepareResult = sut.prepareForPlayback(BookId("book-1"))
            checkNotNull(prepareResult) { "prepareForPlayback must succeed" }
            sut.activateBook(BookId("book-1"))

            sut.startPlayback(player = player, resumePositionMs = 0L, resumeSpeed = 1.0f)
            advanceUntilIdle()

            player.emitState(PlaybackState.Error(message = "transient", isRecoverable = true))
            advanceUntilIdle()
            val midError = sut.playbackError.value
            assertNotNull(midError)
            assertEquals(true, midError.isRecoverable)

            player.emitState(PlaybackState.Playing)
            advanceUntilIdle()
            assertNull(sut.playbackError.value)

            managerScope.coroutineContext[Job]?.cancel()
        }

    @Test
    fun `playerObservationJob notifies progressTracker onPlaybackStarted when player transitions to Playing`() =
        runTest {
            seedBookAndAudioFiles()

            val managerScope = CoroutineScope(coroutineContext + Job())
            val progressTracker = buildFakeProgressTracker(scope = managerScope)

            val sut = createPlaybackManager(scope = managerScope, progressTracker = progressTracker)
            val player = FakePlayer()

            val prepareResult = sut.prepareForPlayback(BookId("book-1"))
            checkNotNull(prepareResult) { "prepareForPlayback must succeed" }
            sut.activateBook(BookId("book-1"))
            sut.startPlayback(player = player, resumePositionMs = 0L, resumeSpeed = 1.0f)
            advanceUntilIdle()

            // FakePlayer's play() emits Playing — verify progressTracker was notified.
            val startedCount = progressTracker.onPlaybackStartedCalls.count { it.first == BookId("book-1") }
            assertTrue(
                startedCount >= 1,
                "expected at least one onPlaybackStarted(BookId(\"book-1\"), …) invocation, got $startedCount",
            )

            managerScope.coroutineContext[Job]?.cancel()
        }

    @Test
    fun `playerObservationJob notifies progressTracker onPlaybackPaused when player transitions to Paused`() =
        runTest {
            seedBookAndAudioFiles()

            val managerScope = CoroutineScope(coroutineContext + Job())
            val progressTracker = buildFakeProgressTracker(scope = managerScope)

            val sut = createPlaybackManager(scope = managerScope, progressTracker = progressTracker)
            val player = FakePlayer()

            val prepareResult = sut.prepareForPlayback(BookId("book-1"))
            checkNotNull(prepareResult) { "prepareForPlayback must succeed" }
            sut.activateBook(BookId("book-1"))
            sut.startPlayback(player = player, resumePositionMs = 0L, resumeSpeed = 1.0f)
            advanceUntilIdle()

            player.emitState(PlaybackState.Paused)
            advanceUntilIdle()

            val pausedCount = progressTracker.onPlaybackPausedCalls.count { it.first == BookId("book-1") }
            assertTrue(
                pausedCount >= 1,
                "expected at least one onPlaybackPaused(BookId(\"book-1\"), …) invocation, got $pausedCount",
            )

            managerScope.coroutineContext[Job]?.cancel()
        }

    // -------------------------------------------------------------------------
    // Drift #28 — Android arm. PlaybackStateWriter (implemented by PlaybackManager)
    // is the seam Android's MediaControllerHolder.Player.Listener pushes state
    // changes through. Verify setPlaybackState routes Playing/Paused transitions
    // to the progressTracker so VMs no longer call it directly. MediaControllerHolder
    // → PlaybackStateWriter forwarding is independently covered by
    // MediaControllerHolderTest in composeApp/src/androidHostTest.
    // -------------------------------------------------------------------------

    @Test
    fun `setPlaybackState Playing notifies progressTracker onPlaybackStarted`() =
        runTest {
            seedBookAndAudioFiles()

            val managerScope = CoroutineScope(coroutineContext + Job())
            val progressTracker = buildFakeProgressTracker(scope = managerScope)

            val sut = createPlaybackManager(scope = managerScope, progressTracker = progressTracker)

            // Activate the book WITHOUT starting an AudioPlayer — this mirrors the
            // Android path where Media3 drives state transitions and the writer
            // pushes them into PlaybackManager.
            checkNotNull(sut.prepareForPlayback(BookId("book-1")))
            sut.activateBook(BookId("book-1"))

            sut.setPlaybackState(PlaybackState.Playing)
            advanceUntilIdle()

            val startedCount = progressTracker.onPlaybackStartedCalls.count { it.first == BookId("book-1") }
            assertTrue(
                startedCount >= 1,
                "expected at least one onPlaybackStarted(BookId(\"book-1\"), …) invocation, got $startedCount",
            )

            managerScope.coroutineContext[Job]?.cancel()
        }

    @Test
    fun `setPlaybackState Paused notifies progressTracker onPlaybackPaused`() =
        runTest {
            seedBookAndAudioFiles()

            val managerScope = CoroutineScope(coroutineContext + Job())
            val progressTracker = buildFakeProgressTracker(scope = managerScope)

            val sut = createPlaybackManager(scope = managerScope, progressTracker = progressTracker)

            checkNotNull(sut.prepareForPlayback(BookId("book-1")))
            sut.activateBook(BookId("book-1"))

            sut.setPlaybackState(PlaybackState.Paused)
            advanceUntilIdle()

            val pausedCount = progressTracker.onPlaybackPausedCalls.count { it.first == BookId("book-1") }
            assertTrue(
                pausedCount >= 1,
                "expected at least one onPlaybackPaused(BookId(\"book-1\"), …) invocation, got $pausedCount",
            )

            managerScope.coroutineContext[Job]?.cancel()
        }

    @Test
    fun `setPlaybackState does not notify progressTracker when no book is active`() =
        runTest {
            val managerScope = CoroutineScope(coroutineContext + Job())
            val progressTracker = buildFakeProgressTracker(scope = managerScope)

            val sut = createPlaybackManager(scope = managerScope, progressTracker = progressTracker)

            // No activateBook() — currentBookId is null.
            sut.setPlaybackState(PlaybackState.Playing)
            sut.setPlaybackState(PlaybackState.Paused)
            advanceUntilIdle()

            assertEquals(0, progressTracker.onPlaybackStartedCalls.size)
            assertEquals(0, progressTracker.onPlaybackPausedCalls.size)

            managerScope.coroutineContext[Job]?.cancel()
        }

    // =========================================================================
    // Fixture helpers
    // =========================================================================

    private fun createPlaybackManager(
        scope: CoroutineScope = CoroutineScope(Job()),
        progressTracker: ProgressTracker = buildProgressTracker(scope = scope),
    ): PlaybackManager {
        val tokenProvider: AudioTokenProvider = mock()
        everySuspend { tokenProvider.prepareForPlayback() } returns Unit

        val serverConfig: ServerConfig = mock()
        everySuspend { serverConfig.getServerUrl() } returns ServerUrl("https://example.test")

        val imageStorage: ImageStorage = mock()
        every { imageStorage.exists(any()) } returns false

        val downloadService: DownloadService = mock()
        everySuspend { downloadService.getLocalPath(any()) } returns null
        everySuspend { downloadService.wasExplicitlyDeleted(any()) } returns false
        everySuspend { downloadService.downloadBook(any()) } returns AppResult.Success(DownloadOutcome.AlreadyDownloaded)

        val playbackPreferences: PlaybackPreferences = mock()
        everySuspend { playbackPreferences.getDefaultPlaybackSpeed() } returns 1.0f

        return PlaybackManagerImpl(
            serverConfig = serverConfig,
            playbackPreferences = playbackPreferences,
            bookDao = db.bookDao(),
            audioFileDao = db.audioFileDao(),
            chapterDao = db.chapterDao(),
            imageStorage = imageStorage,
            progressTracker = progressTracker,
            tokenProvider = tokenProvider,
            deviceContext = DeviceContext(type = DeviceType.Phone),
            downloadService = downloadService,
            playbackApi = null,
            capabilityDetector = null,
            syncApi = null,
            scope = scope,
            bookRepository = mock<BookRepository>(),
        )
    }

    private suspend fun seedBookAndAudioFiles() {
        db.bookDao().upsert(
            BookEntity(
                id = BookId("book-1"),
                libraryId = LibraryId("test-library"),
                folderId = FolderId("test-folder"),
                title = "Test Book",
                sortTitle = "Test Book",
                subtitle = null,
                coverHash = null,
                coverBlurHash = null,
                dominantColor = null,
                darkMutedColor = null,
                vibrantColor = null,
                totalDuration = 1_800_000L,
                description = null,
                publishYear = null,
                publisher = null,
                language = null,
                isbn = null,
                asin = null,
                abridged = false,
                createdAt = Timestamp(1L),
                updatedAt = Timestamp(1L),
            ),
        )
        db.audioFileDao().upsertAll(
            listOf(
                AudioFileEntity(
                    bookId = BookId("book-1"),
                    index = 0,
                    id = "af-0",
                    filename = "chapter1.m4b",
                    format = "m4b",
                    codec = "aac",
                    duration = 1_800_000L,
                    size = 45_000_000L,
                ),
            ),
        )
    }
}
