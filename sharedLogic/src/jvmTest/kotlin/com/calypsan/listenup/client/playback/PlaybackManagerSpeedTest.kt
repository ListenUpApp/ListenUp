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
import com.calypsan.listenup.client.domain.repository.PlaybackPositionRepository
import com.calypsan.listenup.client.domain.repository.PlaybackPreferences
import com.calypsan.listenup.client.domain.repository.PlaybackUpdate
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.DownloadOutcome
import com.calypsan.listenup.client.download.DownloadService
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.matcher.matches
import com.calypsan.listenup.client.data.remote.BookRpcFactory
import dev.mokkery.mock
import dev.mokkery.verify
import dev.mokkery.verify.VerifyMode
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * Bug 3 regression tests for PlaybackManager speed paths.
 *
 * Pins three invariants:
 * 1. onSpeedChanged invokes progressTracker.onSpeedChanged with the new speed
 *    (was already correct, locked here against future regression).
 * 2. startPlayback with effective speed 1.0f calls audioPlayer.setSpeed(1.0f)
 *    even after the player was previously at non-1.0f speed (was suppressed
 *    by the if (speed != 1.0f) guard at PlaybackManager:385-387).
 * 3. onSpeedChanged writes per-book ONLY; never touches the global default
 *    via playbackPreferences.setDefaultPlaybackSpeed (was double-writing
 *    via scope.launch at PlaybackManager:471, conflating per-book and global).
 *
 * If any of these tests regress in the future, the corresponding
 * deletion was likely re-introduced. Investigate before "fixing" the test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackManagerSpeedTest :
    FunSpec({
        fun defaultPlaybackPreferences(): PlaybackPreferences {
            val prefs: PlaybackPreferences = mock()
            everySuspend { prefs.getDefaultPlaybackSpeed() } returns 1.0f
            everySuspend { prefs.setDefaultPlaybackSpeed(any()) } returns Unit
            return prefs
        }

        fun createPlaybackManager(
            db: ListenUpDatabase,
            playbackPreferences: PlaybackPreferences = defaultPlaybackPreferences(),
            progressTracker: ProgressTracker = buildProgressTracker(scope = CoroutineScope(Job())),
            scope: CoroutineScope = CoroutineScope(Job()),
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
                playbackBandwidthCoordinator = DefaultPlaybackBandwidthCoordinator(scope),
            )
        }

        // Creates a [PlaybackManager] whose internal [CoroutineScope] is backed by the
        // [TestScope] from [runTest]. Use this for tests that need [advanceUntilIdle]
        // to drain coroutines launched inside [PlaybackManager] or [ProgressTracker].
        //
        // Returns both the manager and the [PlaybackPositionRepository] mock so tests
        // can assert on it.
        fun TestScope.createPlaybackManagerWithScope(
            db: ListenUpDatabase,
            playbackPreferences: PlaybackPreferences = defaultPlaybackPreferences(),
            positionRepository: PlaybackPositionRepository = defaultPositionRepository(),
        ): Pair<PlaybackManager, PlaybackPositionRepository> {
            val progressTrackerScope = CoroutineScope(coroutineContext)
            val managerScope = CoroutineScope(coroutineContext)

            val progressTracker =
                buildProgressTracker(
                    scope = progressTrackerScope,
                    positionRepository = positionRepository,
                )

            val manager =
                createPlaybackManager(
                    db = db,
                    playbackPreferences = playbackPreferences,
                    progressTracker = progressTracker,
                    scope = managerScope,
                )

            return manager to positionRepository
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

        // -------------------------------------------------------------------------
        // Test 1 — progressTracker write is always invoked regardless of speed value
        // -------------------------------------------------------------------------

        test("onSpeedChanged with 1_0f propagates progressTracker write") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val positionRepository = defaultPositionRepository()

                    val (manager, _) =
                        createPlaybackManagerWithScope(
                            db = db,
                            positionRepository = positionRepository,
                        )

                    manager.activateBook(BookId("book-1"))
                    manager.onSpeedChanged(1.0f)

                    // Drain the scope.launch inside ProgressTracker.onSpeedChanged
                    advanceUntilIdle()

                    verifySuspend(VerifyMode.exactly(1)) {
                        positionRepository.savePlaybackState(
                            any(),
                            matches<PlaybackUpdate>({ "Speed(speed=1.0, custom=true)" }) {
                                it is PlaybackUpdate.Speed && it.speed == 1.0f && it.custom
                            },
                        )
                    }
                }
            } finally {
                db.close()
            }
        }

        // -------------------------------------------------------------------------
        // Test 2 — startPlayback always calls setSpeed, even when speed == 1.0f
        // -------------------------------------------------------------------------

        test("startPlayback with effective speed 1_0f calls audioPlayer setSpeed 1_0f") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    seedBookAndAudioFiles(db)

                    val playbackPreferences: PlaybackPreferences = mock()
                    everySuspend { playbackPreferences.getDefaultPlaybackSpeed() } returns 1.0f

                    val manager =
                        createPlaybackManager(
                            db = db,
                            playbackPreferences = playbackPreferences,
                            // Use a detached scope so the positionMs.collect launch does not
                            // keep the TestScope alive and block runTest completion.
                            scope = CoroutineScope(Job()),
                        )

                    val result = manager.prepareForPlayback(BookId("book-1"))
                    checkNotNull(result) { "prepareForPlayback must succeed" }
                    manager.activateBook(BookId("book-1"))

                    val audioPlayer: AudioPlayer = mock()
                    everySuspend { audioPlayer.load(any()) } returns Unit
                    every { audioPlayer.positionMs } returns MutableStateFlow(0L)
                    every { audioPlayer.state } returns MutableStateFlow(PlaybackState.Idle)
                    every { audioPlayer.setSpeed(any()) } returns Unit
                    every { audioPlayer.play() } returns Unit

                    manager.startPlayback(
                        player = audioPlayer,
                        resumePositionMs = 0L,
                        resumeSpeed = 1.0f,
                    )

                    verify(VerifyMode.exactly(1)) { audioPlayer.setSpeed(1.0f) }
                }
            } finally {
                db.close()
            }
        }

        // -------------------------------------------------------------------------
        // Test 3 — onSpeedChanged writes per-book ONLY; global default stays clean
        // -------------------------------------------------------------------------

        test("onSpeedChanged does not call playbackPreferences setDefaultPlaybackSpeed") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val playbackPreferences: PlaybackPreferences = mock()
                    everySuspend { playbackPreferences.getDefaultPlaybackSpeed() } returns 1.0f
                    everySuspend { playbackPreferences.setDefaultPlaybackSpeed(any()) } returns Unit

                    val positionRepository = defaultPositionRepository()

                    val (manager, _) =
                        createPlaybackManagerWithScope(
                            db = db,
                            playbackPreferences = playbackPreferences,
                            positionRepository = positionRepository,
                        )

                    manager.activateBook(BookId("book-1"))
                    manager.onSpeedChanged(2.0f)

                    // Drain all pending coroutines so any rogue setDefaultPlaybackSpeed call
                    // has had a chance to run before we assert it didn't.
                    advanceUntilIdle()

                    verifySuspend(VerifyMode.exactly(1)) {
                        positionRepository.savePlaybackState(
                            any(),
                            matches<PlaybackUpdate>({ "Speed(speed=2.0, custom=true)" }) {
                                it is PlaybackUpdate.Speed && it.speed == 2.0f && it.custom
                            },
                        )
                    }
                    verifySuspend(VerifyMode.exactly(0)) { playbackPreferences.setDefaultPlaybackSpeed(any()) }
                }
            } finally {
                db.close()
            }
        }
    })
