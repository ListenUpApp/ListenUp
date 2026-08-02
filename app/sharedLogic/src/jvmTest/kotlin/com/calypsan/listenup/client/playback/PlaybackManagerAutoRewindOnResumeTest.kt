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
import com.calypsan.listenup.client.domain.repository.LocalPreferences
import com.calypsan.listenup.client.domain.repository.PlaybackPreferences
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
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * Regression tests for #1220: auto-rewind must also apply on in-session pause→resume, not
 * only at prepare-time.
 *
 * [PlaybackManagerImpl.setPlaying] is the shared isPlaying-transition seam Android
 * (`MediaControllerHolder.Player.Listener`) and Desktop (this class's own player-state
 * observation in [PlaybackManagerImpl.startPlayback]) both funnel every Playing/Paused edge
 * through. These tests cover the manager-level wiring — the seam fires
 * [PlaybackProgressReporter.notePlaybackPaused] / [notePlaybackResumed] on real transitions,
 * [PlaybackManagerImpl.activateBook] resets the pause window so a transition rewind never
 * stacks on top of the prepare-time offset, and the built-in-player path registers a real
 * seek actuator. See [PlaybackProgressReporterAutoRewindTest] for ladder-decision coverage.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackManagerAutoRewindOnResumeTest :
    FunSpec({
        fun defaultPlaybackPreferences(): PlaybackPreferences {
            val prefs: PlaybackPreferences = mock()
            everySuspend { prefs.getDefaultPlaybackSpeed() } returns 1.0f
            everySuspend { prefs.setDefaultPlaybackSpeed(any()) } returns Unit
            return prefs
        }

        fun buildReporter(
            autoRewindEnabled: Boolean,
            nowMillis: () -> Long,
            scope: CoroutineScope,
        ): PlaybackProgressReporter {
            val localPreferences: LocalPreferences = mock()
            every { localPreferences.autoRewindEnabled } returns MutableStateFlow(autoRewindEnabled)
            return PlaybackProgressReporter(
                progressTracker = buildProgressTracker(scope = scope),
                recorder = null,
                scope = scope,
                localPreferences = localPreferences,
                nowMillis = nowMillis,
            )
        }

        fun createManager(
            db: ListenUpDatabase,
            scope: CoroutineScope,
            reporter: PlaybackProgressReporter,
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

            val localPreferences: LocalPreferences = mock()
            every { localPreferences.autoRewindEnabled } returns MutableStateFlow(false)

            return PlaybackManagerImpl(
                serverConfig = serverConfig,
                playbackPreferences = defaultPlaybackPreferences(),
                bookDao = db.bookDao(),
                audioFileDao = db.audioFileDao(),
                chapterDao = db.chapterDao(),
                imageStorage = imageStorage,
                progressTracker = buildProgressTracker(scope = scope),
                reporter = reporter,
                tokenProvider = tokenProvider,
                deviceContext = DeviceContext(type = DeviceType.Phone),
                downloadService = downloadService,
                prepareRepository = testPlaybackPrepareRepository("af-0"),
                channel = RpcChannel.forTest(mock<BookService>()),
                scope = scope,
                bookSyncDomainHandler = mock<SyncDomainHandler<BookSyncPayload>>(),
                localPreferences = localPreferences,
                playbackBandwidthCoordinator = FakePlaybackBandwidthCoordinator(),
                persistTransitionsViaReporter = true,
            )
        }

        suspend fun seedBook(db: ListenUpDatabase) {
            db.bookDao().upsert(
                BookEntity(
                    id = BookId("book-1"),
                    libraryId = LibraryId("test-library"),
                    folderId = FolderId("test-folder"),
                    title = "Test Book",
                    sortTitle = "Test Book",
                    subtitle = null,
                    coverHash = null,
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
                        filename = "chapter0.mp3",
                        format = "mp3",
                        codec = "aac",
                        duration = 1_800_000L,
                        size = 1_000L,
                    ),
                ),
            )
        }

        test("setPlaying(false) then setPlaying(true) after a long gap seeks via the registered actuator") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val scope = CoroutineScope(coroutineContext + Job())
                    var now = 0L
                    val reporter = buildReporter(autoRewindEnabled = true, nowMillis = { now }, scope = scope)
                    val manager = createManager(db, scope, reporter)
                    var seekedMs: Long? = null
                    reporter.onAutoRewindSeek = { seekedMs = it }

                    manager.activateBook(BookId("book-1"))
                    manager.setPlaying(true)
                    manager.setPlaying(false)
                    now += 2 * 3_600_000L
                    manager.setPlaying(true)

                    seekedMs shouldBe 15_000L
                    scope.coroutineContext[Job]?.cancel()
                }
            } finally {
                db.close()
            }
        }

        test("a short pause does not seek") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val scope = CoroutineScope(coroutineContext + Job())
                    var now = 0L
                    val reporter = buildReporter(autoRewindEnabled = true, nowMillis = { now }, scope = scope)
                    val manager = createManager(db, scope, reporter)
                    var seekedMs: Long? = null
                    reporter.onAutoRewindSeek = { seekedMs = it }

                    manager.activateBook(BookId("book-1"))
                    manager.setPlaying(true)
                    manager.setPlaying(false)
                    now += 2_000L
                    manager.setPlaying(true)

                    seekedMs.shouldBeNull()
                    scope.coroutineContext[Job]?.cancel()
                }
            } finally {
                db.close()
            }
        }

        test("activateBook resets the pause window so prepare-time rewind never stacks") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val scope = CoroutineScope(coroutineContext + Job())
                    var now = 0L
                    val reporter = buildReporter(autoRewindEnabled = true, nowMillis = { now }, scope = scope)
                    val manager = createManager(db, scope, reporter)

                    // Book A pauses; a long time passes.
                    manager.activateBook(BookId("book-A"))
                    manager.setPlaying(true)
                    manager.setPlaying(false)
                    now += 2 * 86_400_000L

                    // Book B activates (a fresh prepare) — its own rewind already happened via
                    // PlaybackPreparer. The leftover pause window from book A must not ALSO
                    // fire a transition rewind here.
                    var seeks = 0
                    reporter.onAutoRewindSeek = { seeks++ }
                    manager.activateBook(BookId("book-1"))
                    manager.setPlaying(true)

                    seeks shouldBe 0
                    scope.coroutineContext[Job]?.cancel()
                }
            } finally {
                db.close()
            }
        }

        test("clearPlayback resets the pause window") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val scope = CoroutineScope(coroutineContext + Job())
                    var now = 0L
                    val reporter = buildReporter(autoRewindEnabled = true, nowMillis = { now }, scope = scope)
                    val manager = createManager(db, scope, reporter)

                    manager.activateBook(BookId("book-1"))
                    manager.setPlaying(true)
                    manager.setPlaying(false)
                    now += 2 * 86_400_000L
                    manager.clearPlayback()

                    var seeks = 0
                    reporter.onAutoRewindSeek = { seeks++ }
                    manager.activateBook(BookId("book-1"))
                    manager.setPlaying(true)

                    seeks shouldBe 0
                    scope.coroutineContext[Job]?.cancel()
                }
            } finally {
                db.close()
            }
        }

        test("the built-in-player path registers a working seek actuator on startPlayback") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    seedBook(db)
                    val scope = CoroutineScope(coroutineContext + Job())
                    var now = 0L
                    val reporter = buildReporter(autoRewindEnabled = true, nowMillis = { now }, scope = scope)
                    val manager = createManager(db, scope, reporter)

                    val prepared = manager.prepareForPlayback(BookId("book-1"))
                    checkNotNull(prepared) { "prepareForPlayback must succeed" }
                    manager.activateBook(BookId("book-1"))

                    val player = FakePlayer()
                    manager.startPlayback(player = player, resumePositionMs = 100_000L, resumeSpeed = 1.0f)
                    advanceUntilIdle() // player.play() -> Playing -> setPlaying(true) (no-op: window was just reset)

                    manager.setPlaying(false)
                    now += 2 * 3_600_000L
                    manager.setPlaying(true)
                    advanceUntilIdle()

                    // 15s rung, backed off from the position at the moment of resume (100_000).
                    manager.currentPositionMs.value shouldBe 85_000L

                    scope.coroutineContext[Job]?.cancel()
                }
            } finally {
                db.close()
            }
        }
    })
