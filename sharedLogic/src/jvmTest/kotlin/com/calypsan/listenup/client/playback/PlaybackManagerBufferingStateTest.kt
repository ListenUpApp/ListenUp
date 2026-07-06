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
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.client.data.sync.SyncDomainHandler
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
import com.calypsan.listenup.client.data.remote.BookRpcFactory
import dev.mokkery.mock
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * Verifies the isBuffering and playbackState flows,
 * and the AudioPlayer state observation.
 *
 * These flows serve as the receiving end for platform-specific state pushes:
 * Android's MediaControllerHolder Player.Listener and Desktop's AudioPlayer.state
 * observation in startPlayback.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackManagerBufferingStateTest :
    FunSpec({
        fun createPlaybackManager(
            db: ListenUpDatabase,
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
                reporter = PlaybackProgressReporter(progressTracker, recorder = null, scope = scope),
                tokenProvider = tokenProvider,
                deviceContext = DeviceContext(type = DeviceType.Phone),
                downloadService = downloadService,
                playbackRpcFactory = testPlaybackRpcFactory("af-0"),
                bookRpcFactory = mock<BookRpcFactory>(),
                scope = scope,
                bookSyncDomainHandler = mock<SyncDomainHandler<BookSyncPayload>>(),
            )
        }

        suspend fun seedBookAndAudioFiles(db: ListenUpDatabase) {
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

        test("setBuffering updates isBuffering flow") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val sut = createPlaybackManager(db)

                    sut.isBuffering.value shouldBe false

                    sut.setBuffering(true)
                    sut.isBuffering.value shouldBe true

                    sut.setBuffering(false)
                    sut.isBuffering.value shouldBe false
                }
            } finally {
                db.close()
            }
        }

        test("setPlaybackState updates playbackState flow") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val sut = createPlaybackManager(db)

                    sut.playbackState.value shouldBe PlaybackState.Idle

                    sut.setPlaybackState(PlaybackState.Playing)
                    sut.playbackState.value shouldBe PlaybackState.Playing

                    sut.setPlaybackState(PlaybackState.Buffering)
                    sut.playbackState.value shouldBe PlaybackState.Buffering
                }
            } finally {
                db.close()
            }
        }

        test("startPlayback subscribes to AudioPlayer state and forwards to PlaybackManager") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    seedBookAndAudioFiles(db)

                    // Use coroutineContext + Job() so launched coroutines share the
                    // TestCoroutineScheduler (and are advanced by advanceUntilIdle()) while the
                    // Job is independent of the TestScope — allowing explicit cancellation at the
                    // end without tearing down the test harness.
                    val managerScope = CoroutineScope(coroutineContext + Job())
                    val sut = createPlaybackManager(db, scope = managerScope)

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
                    sut.playbackState.value shouldBe PlaybackState.Playing
                    withClue("Playing state must clear isBuffering") { sut.isBuffering.value shouldBe false }

                    // Now drive the player to Buffering and verify forwarding.
                    player.emitState(PlaybackState.Buffering)
                    advanceUntilIdle()

                    sut.playbackState.value shouldBe PlaybackState.Buffering
                    sut.isBuffering.value shouldBe true

                    // clearPlayback must cancel observations — further emissions must not propagate.
                    sut.clearPlayback()
                    player.emitState(PlaybackState.Playing)
                    advanceUntilIdle()

                    // clearPlayback resets to Idle regardless of what the player emits.
                    sut.playbackState.value shouldBe PlaybackState.Idle
                    withClue("clearPlayback must reset isBuffering to false") { sut.isBuffering.value shouldBe false }

                    // Cancel to stop the infinite collect coroutines and let runTest complete.
                    managerScope.coroutineContext[Job]?.cancel()
                }
            } finally {
                db.close()
            }
        }

        test("playerObservationJob surfaces PlaybackState_Error as PlaybackError on playbackError flow") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    seedBookAndAudioFiles(db)

                    val managerScope = CoroutineScope(coroutineContext + Job())
                    val sut = createPlaybackManager(db, scope = managerScope)
                    val player = FakePlayer()

                    val prepareResult = sut.prepareForPlayback(BookId("book-1"))
                    checkNotNull(prepareResult) { "prepareForPlayback must succeed" }
                    sut.activateBook(BookId("book-1"))

                    sut.startPlayback(player = player, resumePositionMs = 0L, resumeSpeed = 1.0f)
                    advanceUntilIdle()

                    sut.playbackError.value shouldBe null

                    player.emitState(PlaybackState.Error(message = "GStreamer hosed", isRecoverable = false))
                    advanceUntilIdle()

                    val error = sut.playbackError.value.shouldNotBeNull()
                    error.message shouldBe "GStreamer hosed"
                    error.isRecoverable shouldBe false

                    managerScope.coroutineContext[Job]?.cancel()
                }
            } finally {
                db.close()
            }
        }

        test("playerObservationJob clears playbackError on transition to Playing") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    seedBookAndAudioFiles(db)

                    val managerScope = CoroutineScope(coroutineContext + Job())
                    val sut = createPlaybackManager(db, scope = managerScope)
                    val player = FakePlayer()

                    val prepareResult = sut.prepareForPlayback(BookId("book-1"))
                    checkNotNull(prepareResult) { "prepareForPlayback must succeed" }
                    sut.activateBook(BookId("book-1"))

                    sut.startPlayback(player = player, resumePositionMs = 0L, resumeSpeed = 1.0f)
                    advanceUntilIdle()

                    player.emitState(PlaybackState.Error(message = "transient", isRecoverable = true))
                    advanceUntilIdle()
                    val midError = sut.playbackError.value.shouldNotBeNull()
                    midError.isRecoverable shouldBe true

                    player.emitState(PlaybackState.Playing)
                    advanceUntilIdle()
                    sut.playbackError.value shouldBe null

                    managerScope.coroutineContext[Job]?.cancel()
                }
            } finally {
                db.close()
            }
        }

        test("playerObservationJob notifies progressTracker onPlaybackStarted when player transitions to Playing") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    seedBookAndAudioFiles(db)

                    val managerScope = CoroutineScope(coroutineContext + Job())
                    val progressTracker = buildFakeProgressTracker(scope = managerScope)

                    val sut = createPlaybackManager(db, scope = managerScope, progressTracker = progressTracker)
                    val player = FakePlayer()

                    val prepareResult = sut.prepareForPlayback(BookId("book-1"))
                    checkNotNull(prepareResult) { "prepareForPlayback must succeed" }
                    sut.activateBook(BookId("book-1"))
                    sut.startPlayback(player = player, resumePositionMs = 0L, resumeSpeed = 1.0f)
                    advanceUntilIdle()

                    // FakePlayer's play() emits Playing — verify progressTracker was notified.
                    val startedCount = progressTracker.onPlaybackStartedCalls.count { it.first == BookId("book-1") }
                    withClue(
                        "expected at least one onPlaybackStarted(BookId(\"book-1\"), …) invocation, got $startedCount",
                    ) {
                        (startedCount >= 1) shouldBe true
                    }

                    managerScope.coroutineContext[Job]?.cancel()
                }
            } finally {
                db.close()
            }
        }

        test("playerObservationJob notifies progressTracker onPlaybackPaused when player transitions to Paused") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    seedBookAndAudioFiles(db)

                    val managerScope = CoroutineScope(coroutineContext + Job())
                    val progressTracker = buildFakeProgressTracker(scope = managerScope)

                    val sut = createPlaybackManager(db, scope = managerScope, progressTracker = progressTracker)
                    val player = FakePlayer()

                    val prepareResult = sut.prepareForPlayback(BookId("book-1"))
                    checkNotNull(prepareResult) { "prepareForPlayback must succeed" }
                    sut.activateBook(BookId("book-1"))
                    sut.startPlayback(player = player, resumePositionMs = 0L, resumeSpeed = 1.0f)
                    advanceUntilIdle()

                    player.emitState(PlaybackState.Paused)
                    advanceUntilIdle()

                    val pausedCount = progressTracker.onPlaybackPausedCalls.count { it.first == BookId("book-1") }
                    withClue(
                        "expected at least one onPlaybackPaused(BookId(\"book-1\"), …) invocation, got $pausedCount",
                    ) {
                        (pausedCount >= 1) shouldBe true
                    }

                    managerScope.coroutineContext[Job]?.cancel()
                }
            } finally {
                db.close()
            }
        }

        // -------------------------------------------------------------------------
        // Android arm. PlaybackStateWriter (implemented by PlaybackManager)
        // is the seam Android's MediaControllerHolder.Player.Listener pushes state
        // changes through. Verify setPlaybackState routes Playing/Paused transitions
        // to the progressTracker so VMs no longer call it directly. MediaControllerHolder
        // → PlaybackStateWriter forwarding is independently covered by
        // MediaControllerHolderTest in composeApp/src/androidHostTest.
        // -------------------------------------------------------------------------

        test("setPlaybackState Playing notifies progressTracker onPlaybackStarted") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    seedBookAndAudioFiles(db)

                    val managerScope = CoroutineScope(coroutineContext + Job())
                    val progressTracker = buildFakeProgressTracker(scope = managerScope)

                    val sut = createPlaybackManager(db, scope = managerScope, progressTracker = progressTracker)

                    // Activate the book WITHOUT starting an AudioPlayer — this mirrors the
                    // Android path where Media3 drives state transitions and the writer
                    // pushes them into PlaybackManager.
                    checkNotNull(sut.prepareForPlayback(BookId("book-1")))
                    sut.activateBook(BookId("book-1"))

                    sut.setPlaybackState(PlaybackState.Playing)
                    advanceUntilIdle()

                    val startedCount = progressTracker.onPlaybackStartedCalls.count { it.first == BookId("book-1") }
                    withClue(
                        "expected at least one onPlaybackStarted(BookId(\"book-1\"), …) invocation, got $startedCount",
                    ) {
                        (startedCount >= 1) shouldBe true
                    }

                    managerScope.coroutineContext[Job]?.cancel()
                }
            } finally {
                db.close()
            }
        }

        test("setPlaybackState Paused notifies progressTracker onPlaybackPaused") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    seedBookAndAudioFiles(db)

                    val managerScope = CoroutineScope(coroutineContext + Job())
                    val progressTracker = buildFakeProgressTracker(scope = managerScope)

                    val sut = createPlaybackManager(db, scope = managerScope, progressTracker = progressTracker)

                    checkNotNull(sut.prepareForPlayback(BookId("book-1")))
                    sut.activateBook(BookId("book-1"))

                    sut.setPlaybackState(PlaybackState.Paused)
                    advanceUntilIdle()

                    val pausedCount = progressTracker.onPlaybackPausedCalls.count { it.first == BookId("book-1") }
                    withClue(
                        "expected at least one onPlaybackPaused(BookId(\"book-1\"), …) invocation, got $pausedCount",
                    ) {
                        (pausedCount >= 1) shouldBe true
                    }

                    managerScope.coroutineContext[Job]?.cancel()
                }
            } finally {
                db.close()
            }
        }

        test("setPlaybackState does not notify progressTracker when no book is active") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val managerScope = CoroutineScope(coroutineContext + Job())
                    val progressTracker = buildFakeProgressTracker(scope = managerScope)

                    val sut = createPlaybackManager(db, scope = managerScope, progressTracker = progressTracker)

                    // No activateBook() — currentBookId is null.
                    sut.setPlaybackState(PlaybackState.Playing)
                    sut.setPlaybackState(PlaybackState.Paused)
                    advanceUntilIdle()

                    progressTracker.onPlaybackStartedCalls.size shouldBe 0
                    progressTracker.onPlaybackPausedCalls.size shouldBe 0

                    managerScope.coroutineContext[Job]?.cancel()
                }
            } finally {
                db.close()
            }
        }
    })
