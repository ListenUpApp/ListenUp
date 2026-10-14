package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.BookService
import com.calypsan.listenup.api.error.PlaybackError
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
import com.calypsan.listenup.client.domain.repository.LocalPreferences
import com.calypsan.listenup.client.domain.repository.PlaybackPreferences
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.download.DownloadService
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.fake.FakeDownloadRepository
import com.calypsan.listenup.client.test.fake.FakePlaybackBandwidthCoordinator
import com.calypsan.listenup.client.test.fake.FakePlaybackController
import com.calypsan.listenup.client.test.fake.FakePlaybackPositionRepository
import com.calypsan.listenup.client.test.fake.FakePlayer
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.ServerUrl
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.core.error.ErrorBus
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** Where A was when the listener switched to B: the 6c drive run's number. */
private const val PLACE_IN_A_MS = 3_502_545L

private val bookA = BookId("book-a")
private val bookB = BookId("book-b")

/**
 * A switch from a playing book A to a never-played book B whose stream then fails (the server dies between
 * the prepare and the first audio byte), driven through the real [PlayerSession], [ManagedPlayerEngine] and
 * [PlaybackManagerImpl] with Android's wiring (`persistTransitionsViaReporter = false`).
 *
 * B never sounds, so nothing on Android ever reports a position for it: whatever playhead the manager
 * carries over from A is all the session can see. A's place must never be shown as B's, replayed by Retry,
 * or filed under B by Close.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerSessionFailedSwitchTest :
    FunSpec({
        suspend fun seed(
            db: ListenUpDatabase,
            bookId: BookId,
            fileId: String,
        ) {
            db.bookDao().upsert(
                BookEntity(
                    id = bookId,
                    libraryId = LibraryId("test-library"),
                    folderId = FolderId("test-folder"),
                    title = bookId.value,
                    sortTitle = bookId.value,
                    subtitle = null,
                    coverHash = null,
                    totalDuration = 7_200_000L,
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
                        bookId = bookId,
                        index = 0,
                        id = fileId,
                        filename = "$fileId.m4b",
                        format = "m4b",
                        codec = "aac",
                        duration = 7_200_000L,
                        size = 1_000L,
                    ),
                ),
            )
        }

        fun managerOver(
            db: ListenUpDatabase,
            tracker: ProgressTracker,
            scope: CoroutineScope,
            persistTransitionsViaReporter: Boolean,
        ): PlaybackManagerImpl {
            val tokenProvider: AudioTokenProvider = mock()
            everySuspend { tokenProvider.prepareForPlayback() } returns Unit
            val serverConfig: ServerConfig = mock()
            everySuspend { serverConfig.getActiveUrl() } returns ServerUrl("https://example.test")
            val imageStorage: ImageStorage = mock()
            every { imageStorage.exists(any()) } returns false
            val downloadService: DownloadService = mock()
            every { downloadService.supportsDownloads } returns true
            everySuspend { downloadService.getLocalPath(any()) } returns null
            everySuspend { downloadService.getLocalPaths(any()) } returns emptyMap()
            everySuspend { downloadService.wasExplicitlyDeleted(any()) } returns false
            everySuspend { downloadService.downloadBook(any()) } returns
                AppResult.Success(DownloadOutcome.AlreadyDownloaded)
            val playbackPreferences: PlaybackPreferences = mock()
            everySuspend { playbackPreferences.getDefaultPlaybackSpeed() } returns 1.0f
            everySuspend { playbackPreferences.getDefaultVolumeBoostDb() } returns 0.0f
            val localPreferences: LocalPreferences = mock()
            every { localPreferences.autoRewindEnabled } returns MutableStateFlow(false)
            return PlaybackManagerImpl(
                serverConfig = serverConfig,
                playbackPreferences = playbackPreferences,
                bookDao = db.bookDao(),
                audioFileDao = db.audioFileDao(),
                chapterDao = db.chapterDao(),
                imageStorage = imageStorage,
                progressTracker = tracker,
                reporter = PlaybackProgressReporter(tracker, recorder = null, scope = scope, localPreferences = localPreferences),
                tokenProvider = tokenProvider,
                deviceContext = DeviceContext(type = DeviceType.Phone),
                downloadService = downloadService,
                prepareRepository = testPlaybackPrepareRepository("af-a", "af-b"),
                channel = RpcChannel.forTest(mock<BookService>()),
                scope = scope,
                bookSyncDomainHandler = mock<SyncDomainHandler<BookSyncPayload>>(),
                playbackBandwidthCoordinator = FakePlaybackBandwidthCoordinator(),
                localPreferences = localPreferences,
                persistTransitionsViaReporter = persistTransitionsViaReporter,
            )
        }

        class Rig(
            testScope: TestScope,
        ) {
            val db = createInMemoryTestDatabase(StandardTestDispatcher(testScope.testScheduler))
            val positions = FakePlaybackPositionRepository()
            val controller = FakePlaybackController()
            lateinit var manager: PlaybackManagerImpl
            val session =
                PlayerSessionRig(testScope, storedBookId = bookA.value) { scope ->
                    val tracker = ProgressTracker(FakeDownloadRepository(), positions, scope, ErrorBus())
                    // Android's wiring: the service, not the manager, persists transitions.
                    manager = managerOver(db, tracker, scope, persistTransitionsViaReporter = false)
                    ManagedPlayerEngine(
                        playbackManager = manager,
                        playbackController = controller,
                        startGate = NoPlaybackStartGate,
                        progressTracker = tracker,
                        outputRoute = NoOutputRoute,
                        scope = scope,
                    )
                }

            /** A plays and the service's 250 ms poll has put the playhead [PLACE_IN_A_MS] in; then B is asked for and its stream fails. */
            suspend fun switchFromPlayingAToFailedB(
                testScope: TestScope,
                throughGrace: Boolean = true,
            ) {
                seed(db, bookA, "af-a")
                seed(db, bookB, "af-b")
                session.withBook(bookA.value, durationMs = 7_200_000L)
                session.withBook(bookB.value, durationMs = 7_200_000L)
                testScope.runCurrent()
                session.session.playBook(bookA, null)
                testScope.runCurrent()
                manager.setPlaybackState(PlaybackState.Playing)
                manager.setPlaying(true)
                manager.updatePosition(PLACE_IN_A_MS)
                testScope.runCurrent()

                session.session.playBook(bookB, null)
                testScope.runCurrent()
                // The pause beginLoad asked for lands, then Media3 reports B's stream dead ("Source error").
                manager.setPlaying(false)
                manager.reportError(PlaybackError.ConnectionLost())
                testScope.runCurrent()
                if (throughGrace) {
                    testScope.advanceTimeBy(PLAYBACK_DROP_GRACE)
                    testScope.runCurrent()
                }
            }

            fun close() {
                session.close()
                db.close()
            }
        }

        /** The web and desktop wiring: the manager drives an [AudioPlayer] itself and persists its transitions. */
        class BrowserRig(
            testScope: TestScope,
        ) {
            val db = createInMemoryTestDatabase(StandardTestDispatcher(testScope.testScheduler))
            val player = LoadFailingPlayer()
            lateinit var manager: PlaybackManagerImpl
            val session =
                PlayerSessionRig(testScope, storedBookId = bookA.value) { scope ->
                    val tracker =
                        ProgressTracker(FakeDownloadRepository(), FakePlaybackPositionRepository(), scope, ErrorBus())
                    manager = managerOver(db, tracker, scope, persistTransitionsViaReporter = true)
                    ManagedPlayerEngine(
                        playbackManager = manager,
                        playbackController = AudioPlayerController(player, manager),
                        startGate = NoPlaybackStartGate,
                        progressTracker = tracker,
                        outputRoute = NoOutputRoute,
                        scope = scope,
                    )
                }

            fun close() {
                session.close()
                db.close()
            }
        }

        test("web and desktop: a switch whose load throws leaves nothing for the tab-hide flush to save as B's") {
            runTest {
                val rig = BrowserRig(this)
                try {
                    seed(rig.db, bookA, "af-a")
                    seed(rig.db, bookB, "af-b")
                    rig.session.withBook(bookA.value, durationMs = 7_200_000L)
                    rig.session.withBook(bookB.value, durationMs = 7_200_000L)
                    runCurrent()
                    rig.session.session.playBook(bookA, null)
                    runCurrent()
                    rig.player.fake.advancePosition(PLACE_IN_A_MS)
                    runCurrent()
                    rig.manager.currentPositionMs.value shouldBe PLACE_IN_A_MS

                    // hls.js cannot be fetched from the server that just died: the load itself throws.
                    rig.player.failNextLoad = true
                    rig.session.session.playBook(bookB, null)
                    runCurrent()

                    // The engine let go of the book it could not start: the flush pairs nothing with B, least of
                    // all A's place.
                    rig.manager.currentBookId.value shouldBe null
                    rig.manager.currentPositionMs.value shouldBe 0L
                } finally {
                    rig.close()
                }
            }
        }

        test("web and desktop: a load that throws after the book was activated stays Failed and shows its error") {
            runTest {
                val rig = BrowserRig(this)
                try {
                    seed(rig.db, bookA, "af-a")
                    seed(rig.db, bookB, "af-b")
                    rig.session.withBook(bookA.value, durationMs = 7_200_000L)
                    rig.session.withBook(bookB.value, durationMs = 7_200_000L)
                    runCurrent()
                    rig.session.session.playBook(bookA, null)
                    runCurrent()

                    rig.player.failNextLoad = true
                    rig.session.session.playBook(bookB, null)
                    runCurrent()

                    rig.session.loaded.bookId shouldBe bookB
                    rig.session.loaded.phase
                        .shouldBeInstanceOf<PlayerPhase.Failed>()
                    (rig.session.loaded.phase as PlayerPhase.Failed).error.shouldBeInstanceOf<PlaybackError.CouldNotStart>()
                } finally {
                    rig.close()
                }
            }
        }

        test("a failed switch to B never shows A's place as B's") {
            runTest {
                val rig = Rig(this)
                try {
                    rig.switchFromPlayingAToFailedB(this)

                    rig.session.loaded.bookId shouldBe bookB
                    rig.session.loaded.phase
                        .shouldBeInstanceOf<PlayerPhase.Failed>()
                    rig.session.session.positionMs.value shouldBe 0L
                } finally {
                    rig.close()
                }
            }
        }

        test("Retry after a failed switch starts B at B's own place, not A's") {
            runTest {
                val rig = Rig(this)
                try {
                    rig.switchFromPlayingAToFailedB(this)

                    rig.session.session.retry()
                    runCurrent()

                    val restart = rig.controller.startPlaybackCalls.last()
                    restart.timeline.bookId shouldBe bookB
                    restart.resumePositionMs shouldBe 0L
                } finally {
                    rig.close()
                }
            }
        }

        test("inside the drop's grace B still shows its own place, and Close files nothing of A's under B") {
            runTest {
                val rig = Rig(this)
                try {
                    rig.switchFromPlayingAToFailedB(this, throughGrace = false)
                    rig.session.loaded.bookId shouldBe bookB
                    rig.session.session.positionMs.value shouldBe 0L

                    rig.session.session.closeBook()
                    runCurrent()

                    val savedB = (rig.positions.get(bookB) as AppResult.Success).data?.positionMs
                    (savedB ?: 0L) shouldBe 0L
                } finally {
                    rig.close()
                }
            }
        }

        test("Close after a failed switch never files A's place under B") {
            runTest {
                val rig = Rig(this)
                try {
                    rig.switchFromPlayingAToFailedB(this)

                    rig.session.session.closeBook()
                    runCurrent()

                    val savedB = (rig.positions.get(bookB) as AppResult.Success).data?.positionMs
                    (savedB ?: 0L) shouldBe 0L
                } finally {
                    rig.close()
                }
            }
        }
    })

/** A [FakePlayer] whose next [load] throws, as the browser's does when hls.js cannot be fetched. */
private class LoadFailingPlayer(
    val fake: FakePlayer = FakePlayer(),
) : AudioPlayer by fake {
    var failNextLoad = false

    override suspend fun load(segments: List<AudioSegment>) {
        if (failNextLoad) {
            failNextLoad = false
            error("hls.js could not be fetched")
        }
        fake.load(segments)
    }
}

/** The browser's controller shape (`WebPlaybackController`): the manager starts the [AudioPlayer] itself. */
private class AudioPlayerController(
    private val player: AudioPlayer,
    private val manager: PlaybackManager,
) : PlaybackController {
    override val ready: StateFlow<Boolean> = MutableStateFlow(true)

    override fun acquire() = Unit

    override fun releasePlayer() = Unit

    override fun play() = player.play()

    override fun pause() = player.pause()

    override fun seekTo(positionMs: Long) = player.seekTo(positionMs)

    override fun setPlaybackSpeed(speed: Float) = player.setSpeed(speed)

    override fun stop() = player.pause()

    override fun setVolume(volume: Float) = Unit

    override suspend fun setMediaQueue(
        items: List<PlaybackMediaItem>,
        startPositionMs: Long,
    ) = Unit

    override suspend fun startPlayback(prepareResult: PlaybackManager.PrepareResult) {
        manager.startPlayback(player, prepareResult.resumePositionMs, prepareResult.resumeSpeed)
    }
}
