package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.BookService
import com.calypsan.listenup.api.dto.auth.DeviceInfo
import com.calypsan.listenup.api.error.PlaybackError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.getOrNull
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.client.data.local.db.AudioFileEntity
import com.calypsan.listenup.client.data.local.db.BookEntity
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.ListeningEventEntity
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.data.sync.SyncDomainHandler
import com.calypsan.listenup.client.device.DeviceContext
import com.calypsan.listenup.client.device.DeviceInfoProvider
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
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone

private const val BOOK = "book-1"
private const val USER_ID = "user-test-1"
private const val FILE_MS = 3_600_000L

/**
 * The listening history the web and desktop players produce, end to end: a [FakePlayer] drives the real
 * [PlaybackManagerImpl], whose reporter records into a real [ListeningEventRecorder] over an in-memory database.
 */
class PlaybackManagerListeningSpanTest :
    FunSpec({

        test("a stall between two Playing reports leaves one span from the first play to the pause") {
            runTest {
                withPlayingBook { player, db, setNow, _ ->
                    setNow(601_000L)
                    player.advancePosition(600_000L)
                    advanceUntilIdle()

                    // The web element stalls (`waiting`), then plays again (`playing`); a file change does the same.
                    player.emitState(PlaybackState.Buffering)
                    advanceUntilIdle()
                    setNow(605_000L)
                    player.emitState(PlaybackState.Playing)
                    advanceUntilIdle()
                    db.tentativeSpanDao().countRows() shouldBe 1

                    setNow(901_000L)
                    player.advancePosition(900_000L)
                    advanceUntilIdle()
                    player.pause()
                    advanceUntilIdle()

                    db.spans() shouldBe listOf(0L to 900_000L)
                    db.tentativeSpanDao().get().shouldBeNull()
                }
            }
        }

        test("a seek splits the span, so the jumped-over range is not counted as listened") {
            runTest {
                withPlayingBook { player, db, setNow, manager ->
                    setNow(121_000L)
                    player.advancePosition(120_000L)
                    advanceUntilIdle()

                    // What ManagedPlayerEngine.seekTo does: move the player, then tell the manager where from and to.
                    player.seekTo(1_500_000L)
                    manager.onSeek(fromPositionMs = 120_000L, toPositionMs = 1_500_000L)
                    advanceUntilIdle()

                    setNow(181_000L)
                    player.advancePosition(1_560_000L)
                    advanceUntilIdle()
                    player.pause()
                    advanceUntilIdle()

                    db.spans() shouldBe listOf(0L to 120_000L, 1_500_000L to 1_560_000L)
                }
            }
        }

        test("a player failure closes the span where the audio stopped, and a retry starts a new one") {
            runTest {
                withPlayingBook { player, db, setNow, _ ->
                    setNow(301_000L)
                    player.advancePosition(300_000L)
                    advanceUntilIdle()
                    player.emitState(PlaybackState.Error(PlaybackError.ConnectionLost()))
                    advanceUntilIdle()

                    db.spans() shouldBe listOf(0L to 300_000L)
                    db.tentativeSpanDao().get().shouldBeNull()
                }
            }
        }

        test("a teardown while buffering closes the span: the next play of the book is a new listen") {
            runTest {
                withPlayingBook { player, db, setNow, manager ->
                    setNow(301_000L)
                    player.advancePosition(300_000L)
                    advanceUntilIdle()
                    player.emitState(PlaybackState.Buffering)
                    advanceUntilIdle()

                    manager.clearPlayback()
                    advanceUntilIdle()

                    db.spans() shouldBe listOf(0L to 300_000L)
                    db.tentativeSpanDao().get().shouldBeNull()
                }
            }
        }
    })

private suspend fun ListenUpDatabase.spans(): List<Pair<Long, Long>> =
    listeningEventDao()
        .getByBookForUser(USER_ID, BOOK)
        .sortedBy(ListeningEventEntity::startedAt)
        .map { it.startPositionMs to it.endPositionMs }

/**
 * Prepare and start a one-file book on a [FakePlayer] from position 0 at wall time 1 000 ms, then run [block].
 * `setNow` moves the recorder's clock.
 */
private suspend fun TestScope.withPlayingBook(
    block: suspend TestScope.(
        player: FakePlayer,
        db: ListenUpDatabase,
        setNow: (Long) -> Unit,
        manager: PlaybackManagerImpl,
    ) -> Unit,
) {
    val db = createInMemoryTestDatabase(StandardTestDispatcher(testScheduler))
    val managerScope = CoroutineScope(coroutineContext + Job())
    try {
        var now = 1_000L
        seedBook(db)
        val manager = buildManager(db, managerScope) { now }
        manager.prepareForPlayback(BookId(BOOK), start = PlaybackStart.Resume).getOrNull()
            ?: error("prepareForPlayback must succeed")
        manager.activateBook(BookId(BOOK))
        val player = FakePlayer()
        manager.startPlayback(player = player, resumePositionMs = 0L, resumeSpeed = 1.0f)
        advanceUntilIdle()

        block(player, db, { now = it }, manager)
    } finally {
        managerScope.coroutineContext.job.cancelAndJoin()
        db.close()
    }
}

private fun buildManager(
    db: ListenUpDatabase,
    scope: CoroutineScope,
    nowMillis: () -> Long,
): PlaybackManagerImpl {
    val prefs: PlaybackPreferences = mock()
    everySuspend { prefs.getDefaultPlaybackSpeed() } returns 1.0f
    everySuspend { prefs.getDefaultVolumeBoostDb() } returns 0.0f

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
    everySuspend { downloadService.downloadBook(any()) } returns AppResult.Success(DownloadOutcome.AlreadyDownloaded)

    val localPreferences: LocalPreferences = mock()
    every { localPreferences.autoRewindEnabled } returns MutableStateFlow(false)

    val recorder =
        ListeningEventRecorder(
            listeningEventDao = db.listeningEventDao(),
            tentativeSpanDao = db.tentativeSpanDao(),
            transactionRunner = RoomTransactionRunner(db),
            enqueue = { _, _, _ -> },
            currentUserId = { USER_ID },
            deviceInfo = DeviceInfoProvider { DeviceInfo(deviceName = "Test Device") },
            clock =
                object : Clock {
                    override fun now(): Instant = Instant.fromEpochMilliseconds(nowMillis())
                },
            timeZone = { TimeZone.UTC },
        )
    val progressTracker = buildProgressTracker(scope = scope)
    return PlaybackManagerImpl(
        serverConfig = serverConfig,
        playbackPreferences = prefs,
        bookDao = db.bookDao(),
        audioFileDao = db.audioFileDao(),
        chapterDao = db.chapterDao(),
        imageStorage = imageStorage,
        progressTracker = progressTracker,
        reporter =
            PlaybackProgressReporter(
                progressTracker = progressTracker,
                recorder = recorder,
                scope = scope,
                localPreferences = localPreferences,
                nowMillis = nowMillis,
            ),
        tokenProvider = tokenProvider,
        deviceContext = DeviceContext(type = DeviceType.Desktop),
        downloadService = downloadService,
        prepareRepository = testPlaybackPrepareRepository("af-0"),
        channel = RpcChannel.forTest(mock<BookService>()),
        scope = scope,
        bookSyncDomainHandler = mock<SyncDomainHandler<BookSyncPayload>>(),
        playbackBandwidthCoordinator = FakePlaybackBandwidthCoordinator(),
        localPreferences = localPreferences,
    )
}

private suspend fun seedBook(db: ListenUpDatabase) {
    db.bookDao().upsert(
        BookEntity(
            id = BookId(BOOK),
            libraryId = LibraryId("test-library"),
            folderId = FolderId("test-folder"),
            title = "Test Book",
            sortTitle = "Test Book",
            subtitle = null,
            coverHash = null,
            totalDuration = FILE_MS,
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
                bookId = BookId(BOOK),
                index = 0,
                id = "af-0",
                filename = "book.m4b",
                format = "m4b",
                codec = "aac",
                duration = FILE_MS,
                size = 1_000L,
            ),
        ),
    )
}
