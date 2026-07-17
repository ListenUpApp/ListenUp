package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.BookService
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.client.data.local.db.AudioFileEntity
import com.calypsan.listenup.client.data.local.db.BookEntity
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.data.sync.SyncDomainHandler
import com.calypsan.listenup.client.device.DeviceContext
import com.calypsan.listenup.client.device.DeviceType
import com.calypsan.listenup.client.domain.model.DownloadOutcome
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.PlaybackPositionRepository
import com.calypsan.listenup.client.domain.repository.PlaybackPreferences
import com.calypsan.listenup.client.domain.repository.PlaybackUpdate
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.download.DownloadService
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.fake.FakePlaybackBandwidthCoordinator
import com.calypsan.listenup.client.test.fake.FakePlayer
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.ServerUrl
import com.calypsan.listenup.core.Timestamp
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.matcher.matches
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * Regression tests for two position-integrity fixes on multi-file books:
 *
 * - **F2** — Android's position poll pushes ExoPlayer FILE-relative coordinates
 *   ([PlaybackStateWriter.updatePositionFromMediaItem]). [PlaybackManagerImpl] must
 *   convert them to a BOOK-relative position via the active timeline before storing.
 *   Feeding the raw file offset into `currentPositionMs` regresses every persisted
 *   position and corrupts skip/seek math for a book in a late file.
 *
 * - **F10** — Android's `PlaybackService.PlayerListener` already persists book-relative
 *   transitions, so [PlaybackManagerImpl] must NOT also persist Playing/Paused via the
 *   reporter when `persistTransitionsViaReporter = false` (avoids a second outbox write
 *   per play/pause). Desktop keeps the default `true`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackManagerPositionTransitionTest :
    FunSpec({
        fun defaultPlaybackPreferences(): PlaybackPreferences {
            val prefs: PlaybackPreferences = mock()
            everySuspend { prefs.getDefaultPlaybackSpeed() } returns 1.0f
            everySuspend { prefs.setDefaultPlaybackSpeed(any()) } returns Unit
            return prefs
        }

        fun createManager(
            db: ListenUpDatabase,
            scope: CoroutineScope,
            progressTracker: ProgressTracker = buildProgressTracker(scope = scope),
            audioFileIds: Array<String> = arrayOf("af-0"),
            persistTransitionsViaReporter: Boolean = true,
        ): PlaybackManager {
            val tokenProvider: AudioTokenProvider = mock()
            everySuspend { tokenProvider.prepareForPlayback() } returns Unit

            val serverConfig: ServerConfig = mock()
            everySuspend { serverConfig.getActiveUrl() } returns ServerUrl("https://example.test")

            val imageStorage: ImageStorage = mock()
            every { imageStorage.exists(any()) } returns false

            val downloadService: DownloadService = mock()
            everySuspend { downloadService.getLocalPath(any()) } returns null
            everySuspend { downloadService.wasExplicitlyDeleted(any()) } returns false
            everySuspend { downloadService.downloadBook(any()) } returns AppResult.Success(DownloadOutcome.AlreadyDownloaded)

            return PlaybackManagerImpl(
                serverConfig = serverConfig,
                playbackPreferences = defaultPlaybackPreferences(),
                bookDao = db.bookDao(),
                audioFileDao = db.audioFileDao(),
                chapterDao = db.chapterDao(),
                imageStorage = imageStorage,
                progressTracker = progressTracker,
                reporter = PlaybackProgressReporter(progressTracker, recorder = null, scope = scope),
                tokenProvider = tokenProvider,
                deviceContext = DeviceContext(type = DeviceType.Phone),
                downloadService = downloadService,
                prepareRepository = testPlaybackPrepareRepository(*audioFileIds),
                channel = RpcChannel.forTest(mock<BookService>()),
                scope = scope,
                bookSyncDomainHandler = mock<SyncDomainHandler<BookSyncPayload>>(),
                playbackBandwidthCoordinator = FakePlaybackBandwidthCoordinator(),
                persistTransitionsViaReporter = persistTransitionsViaReporter,
            )
        }

        // Seeds a book with [fileCount] audio files, each [fileDurationMs] long.
        suspend fun seedBook(
            db: ListenUpDatabase,
            fileCount: Int,
            fileDurationMs: Long,
        ) {
            db.bookDao().upsert(
                BookEntity(
                    id = BookId("book-1"),
                    libraryId = LibraryId("test-library"),
                    folderId = FolderId("test-folder"),
                    title = "Test Book",
                    sortTitle = "Test Book",
                    subtitle = null,
                    coverHash = null,
                    totalDuration = fileDurationMs * fileCount,
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
                (0 until fileCount).map { i ->
                    AudioFileEntity(
                        bookId = BookId("book-1"),
                        index = i,
                        id = "af-$i",
                        filename = "chapter$i.mp3",
                        format = "mp3",
                        codec = "aac",
                        duration = fileDurationMs,
                        size = 1_000L,
                    )
                },
            )
        }

        // ---------------------------------------------------------------------
        // F2 — file-relative → book-relative conversion at the poll seam
        // ---------------------------------------------------------------------

        test("updatePositionFromMediaItem converts a late-file offset to a book-relative position") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    seedBook(db, fileCount = 3, fileDurationMs = 3_600_000L)
                    val manager = createManager(db, CoroutineScope(Job()), audioFileIds = arrayOf("af-0", "af-1", "af-2"))

                    val prepared = manager.prepareForPlayback(BookId("book-1"))
                    checkNotNull(prepared) { "prepareForPlayback must succeed" }
                    manager.activateBook(BookId("book-1"))

                    // Poll reports file index 2, 10 minutes into that file.
                    manager.updatePositionFromMediaItem(mediaItemIndex = 2, positionInItemMs = 600_000L)

                    // Book position = 2 × 3_600_000 + 600_000 = 7_800_000, NOT the raw 600_000.
                    manager.currentPositionMs.value shouldBe 7_800_000L
                }
            } finally {
                db.close()
            }
        }

        test("updatePositionFromMediaItem falls back to the raw offset when no timeline is active") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val manager = createManager(db, CoroutineScope(Job()))
                    // No prepareForPlayback → currentTimeline is null.
                    manager.updatePositionFromMediaItem(mediaItemIndex = 3, positionInItemMs = 12_345L)
                    manager.currentPositionMs.value shouldBe 12_345L
                }
            } finally {
                db.close()
            }
        }

        // ---------------------------------------------------------------------
        // F10 — transition persistence is gated by persistTransitionsViaReporter
        // ---------------------------------------------------------------------

        fun TestScope.managerWith(
            db: ListenUpDatabase,
            positionRepository: PlaybackPositionRepository,
            persistTransitionsViaReporter: Boolean,
        ): PlaybackManager {
            val scope = CoroutineScope(coroutineContext)
            val progressTracker = buildProgressTracker(scope = scope, positionRepository = positionRepository)
            return createManager(
                db = db,
                scope = scope,
                progressTracker = progressTracker,
                persistTransitionsViaReporter = persistTransitionsViaReporter,
            )
        }

        test("setPlaybackState(Playing) does NOT persist when persistTransitionsViaReporter is false") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val positionRepository = defaultPositionRepository()
                    val manager = managerWith(db, positionRepository, persistTransitionsViaReporter = false)

                    manager.activateBook(BookId("book-1"))
                    manager.setPlaybackState(PlaybackState.Playing)
                    manager.setPlaybackState(PlaybackState.Paused)
                    advanceUntilIdle()

                    verifySuspend(VerifyMode.exactly(0)) {
                        positionRepository.savePlaybackState(any(), any())
                    }
                    // UI state still advances — only persistence is gated.
                    manager.playbackState.value shouldBe PlaybackState.Paused
                }
            } finally {
                db.close()
            }
        }

        test("setPlaybackState(Playing) persists when persistTransitionsViaReporter is true") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val positionRepository = defaultPositionRepository()
                    val manager = managerWith(db, positionRepository, persistTransitionsViaReporter = true)

                    manager.activateBook(BookId("book-1"))
                    manager.setPlaybackState(PlaybackState.Playing)
                    advanceUntilIdle()

                    verifySuspend(VerifyMode.exactly(1)) {
                        positionRepository.savePlaybackState(
                            any(),
                            matches<PlaybackUpdate>({ "PlaybackStarted" }) { it is PlaybackUpdate.PlaybackStarted },
                        )
                    }
                }
            } finally {
                db.close()
            }
        }

        // ---------------------------------------------------------------------
        // F5 — the built-in-player (Desktop) observation path persists periodically.
        // Desktop has no external periodic persister (Android → PlaybackService 30 s,
        // iOS → PlayerCoordinator 5 s), so without this a crash mid-session loses the
        // whole session and the listening span is dropped as zero-duration.
        // ---------------------------------------------------------------------

        test("built-in-player path persists periodically via the reporter as content advances") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    seedBook(db, fileCount = 1, fileDurationMs = 1_800_000L)
                    val managerScope = CoroutineScope(coroutineContext + Job())
                    val positionRepository = defaultPositionRepository()
                    val progressTracker = buildProgressTracker(scope = managerScope, positionRepository = positionRepository)
                    val manager =
                        createManager(
                            db = db,
                            scope = managerScope,
                            progressTracker = progressTracker,
                            persistTransitionsViaReporter = true,
                        )

                    val prepared = manager.prepareForPlayback(BookId("book-1"))
                    checkNotNull(prepared) { "prepareForPlayback must succeed" }
                    manager.activateBook(BookId("book-1"))

                    val player = FakePlayer()
                    manager.startPlayback(player = player, resumePositionMs = 0L, resumeSpeed = 1.0f)
                    advanceUntilIdle() // player.play() → Playing → isPlaying = true

                    // Advance content past the 10 s persist interval (the built-in player emits this
                    // continuously; drive it directly).
                    player.advancePosition(10_000L)
                    advanceUntilIdle()

                    // The periodic tick persisted the current position via a PeriodicUpdate — the same
                    // reporter.onPositionUpdate call also advances the listening-span heartbeat.
                    verifySuspend(VerifyMode.exactly(1)) {
                        positionRepository.savePlaybackState(
                            any(),
                            matches<PlaybackUpdate>({ "PeriodicUpdate(10_000)" }) {
                                it is PlaybackUpdate.PeriodicUpdate && it.positionMs == 10_000L
                            },
                        )
                    }

                    managerScope.coroutineContext[Job]?.cancel()
                }
            } finally {
                db.close()
            }
        }

        test("Android-style instance (persistTransitionsViaReporter=false) does NOT periodically persist") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    seedBook(db, fileCount = 1, fileDurationMs = 1_800_000L)
                    val managerScope = CoroutineScope(coroutineContext + Job())
                    val positionRepository = defaultPositionRepository()
                    val progressTracker = buildProgressTracker(scope = managerScope, positionRepository = positionRepository)
                    val manager =
                        createManager(
                            db = db,
                            scope = managerScope,
                            progressTracker = progressTracker,
                            persistTransitionsViaReporter = false,
                        )

                    val prepared = manager.prepareForPlayback(BookId("book-1"))
                    checkNotNull(prepared) { "prepareForPlayback must succeed" }
                    manager.activateBook(BookId("book-1"))

                    val player = FakePlayer()
                    manager.startPlayback(player = player, resumePositionMs = 0L, resumeSpeed = 1.0f)
                    advanceUntilIdle()

                    player.advancePosition(10_000L)
                    advanceUntilIdle()

                    // Android owns its own PlaybackService periodic loop — this class must not double-drive.
                    verifySuspend(VerifyMode.exactly(0)) {
                        positionRepository.savePlaybackState(
                            any(),
                            matches<PlaybackUpdate>({ "PeriodicUpdate" }) { it is PlaybackUpdate.PeriodicUpdate },
                        )
                    }

                    managerScope.coroutineContext[Job]?.cancel()
                }
            } finally {
                db.close()
            }
        }
    })
