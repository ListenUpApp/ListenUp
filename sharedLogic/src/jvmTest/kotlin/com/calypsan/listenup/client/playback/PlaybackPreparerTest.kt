package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.PlaybackService
import com.calypsan.listenup.api.dto.PreparedAudioFile
import com.calypsan.listenup.api.dto.PreparedPlayback as ContractPreparedPlayback
import com.calypsan.listenup.api.dto.RecordListeningEventRequest
import com.calypsan.listenup.api.dto.RecordPositionRequest
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.ListeningEventSyncPayload
import com.calypsan.listenup.api.sync.PlaybackPositionSyncPayload
import com.calypsan.listenup.api.sync.UserStatsSyncPayload
import com.calypsan.listenup.client.data.local.db.AudioFileEntity
import com.calypsan.listenup.client.data.local.db.BookEntity
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.remote.BookRpcFactory
import com.calypsan.listenup.client.data.remote.PlaybackRpcFactory
import com.calypsan.listenup.client.device.DeviceContext
import com.calypsan.listenup.client.device.DeviceType
import com.calypsan.listenup.client.domain.model.DownloadOutcome
import com.calypsan.listenup.client.data.sync.SyncDomainHandler
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.PlaybackPreferences
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.download.DownloadService
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
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
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest

/**
 * Unit-integration tests for [PlaybackPreparer]'s offline-first / streaming-URL strategy.
 *
 * Covers the three critical paths:
 * 1. Fully downloaded → [PlaybackService.prepare] is never called; local paths are set.
 * 2. Streaming → prepare called exactly once; signed URLs are absolute and well-formed.
 * 3. Not downloaded + prepare fails → [PlaybackPreparer.prepare] returns null.
 */
class PlaybackPreparerTest :
    FunSpec({

        val db: ListenUpDatabase = createInMemoryTestDatabase()

        afterSpec { db.close() }

        // ── shared constants ───────────────────────────────────────────────────────────

        val bookId = BookId("book-prep-1")
        val serverUrl = "https://server.test"
        val audioFile1 = "af-prep-1"
        val audioFile2 = "af-prep-2"

        // ── seed helpers ───────────────────────────────────────────────────────────────

        suspend fun seedBookAndAudioFiles() {
            db.bookDao().upsert(
                BookEntity(
                    id = bookId,
                    libraryId = LibraryId("lib-1"),
                    folderId = FolderId("folder-1"),
                    title = "Prepare Test Book",
                    sortTitle = "Prepare Test Book",
                    subtitle = null,
                    coverHash = null,
                    coverBlurHash = null,
                    totalDuration = 3_000L,
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
                        id = audioFile1,
                        filename = "01.mp3",
                        format = "mp3",
                        codec = "mp3",
                        duration = 1_000L,
                        size = 1_000L,
                    ),
                    AudioFileEntity(
                        bookId = bookId,
                        index = 1,
                        id = audioFile2,
                        filename = "02.mp3",
                        format = "mp3",
                        codec = "mp3",
                        duration = 2_000L,
                        size = 2_000L,
                    ),
                ),
            )
        }

        // Seed book + audio-file rows before every test so each test is fully
        // self-contained and execution order has no effect on correctness.
        beforeTest {
            db.audioFileDao().deleteForBook(bookId.value)
            db.bookDao().deleteById(bookId)
            seedBookAndAudioFiles()
        }

        // ── fake factory builder ───────────────────────────────────────────────────────

        fun buildPreparer(
            downloadService: DownloadService,
            playbackRpcFactory: PlaybackRpcFactory,
        ): PlaybackPreparer {
            val tokenProvider: AudioTokenProvider = mock()
            everySuspend { tokenProvider.prepareForPlayback() } returns Unit

            val serverConfig: ServerConfig = mock()
            everySuspend { serverConfig.getServerUrl() } returns ServerUrl(serverUrl)

            val imageStorage: ImageStorage = mock()
            every { imageStorage.exists(any()) } returns false

            val playbackPreferences: PlaybackPreferences = mock()
            everySuspend { playbackPreferences.getDefaultPlaybackSpeed() } returns 1.0f

            return PlaybackPreparer(
                serverConfig = serverConfig,
                playbackPreferences = playbackPreferences,
                bookDao = db.bookDao(),
                audioFileDao = db.audioFileDao(),
                chapterDao = db.chapterDao(),
                imageStorage = imageStorage,
                progressTracker = buildProgressTracker(),
                tokenProvider = tokenProvider,
                deviceContext = DeviceContext(type = DeviceType.Phone),
                downloadService = downloadService,
                playbackRpcFactory = playbackRpcFactory,
                bookRpcFactory = mock<BookRpcFactory>(),
                scope = CoroutineScope(Job()),
                bookSyncDomainHandler = mock<SyncDomainHandler<BookSyncPayload>>(),
            )
        }

        // ── test 1: fully downloaded ───────────────────────────────────────────────────

        test("fully downloaded — prepare() is never called; local paths are set") {
            runTest {
                val fakePlaybackService =
                    FakePlaybackService(
                        prepareResult =
                            AppResult.Success(
                                ContractPreparedPlayback(
                                    bookId = bookId.value,
                                    audioFiles =
                                        listOf(
                                            PreparedAudioFile(
                                                audioFile1,
                                                0,
                                                "/api/v1/audio/book-prep-1/$audioFile1?sig=x",
                                                "mp3",
                                                1_000L,
                                                1_000L,
                                            ),
                                            PreparedAudioFile(
                                                audioFile2,
                                                1,
                                                "/api/v1/audio/book-prep-1/$audioFile2?sig=y",
                                                "mp3",
                                                2_000L,
                                                2_000L,
                                            ),
                                        ),
                                    resumePosition = null,
                                ),
                            ),
                    )
                val fakeFactory = FakePlaybackRpcFactory(fakePlaybackService)

                val downloadService: DownloadService = mock()
                everySuspend { downloadService.getLocalPath(audioFile1) } returns "/local/af-prep-1.mp3"
                everySuspend { downloadService.getLocalPath(audioFile2) } returns "/local/af-prep-2.mp3"
                everySuspend { downloadService.wasExplicitlyDeleted(any()) } returns false
                everySuspend { downloadService.downloadBook(any()) } returns
                    AppResult
                        .Success(DownloadOutcome.AlreadyDownloaded)

                val preparer = buildPreparer(downloadService, fakeFactory)
                val result = preparer.prepare(bookId)

                result.shouldNotBeNull()
                fakePlaybackService.prepareCallCount shouldBe 0

                result.timeline.files[0].localPath shouldBe "/local/af-prep-1.mp3"
                result.timeline.files[1].localPath shouldBe "/local/af-prep-2.mp3"
                // Streaming URL is empty when fully downloaded
                result.timeline.files[0].streamingUrl shouldBe ""
                result.timeline.files[1].streamingUrl shouldBe ""
            }
        }

        // ── test 2: streaming ──────────────────────────────────────────────────────────

        test("streaming — prepare() called once; signed URLs are absolute and well-formed") {
            runTest {
                val signedPath1 = "/api/v1/audio/book-prep-1/$audioFile1?u=&exp=100&sig=abc"
                val signedPath2 = "/api/v1/audio/book-prep-1/$audioFile2?u=&exp=100&sig=def"

                val fakePlaybackService =
                    FakePlaybackService(
                        prepareResult =
                            AppResult.Success(
                                ContractPreparedPlayback(
                                    bookId = bookId.value,
                                    audioFiles =
                                        listOf(
                                            PreparedAudioFile(audioFile1, 0, signedPath1, "mp3", 1_000L, 1_000L),
                                            PreparedAudioFile(audioFile2, 1, signedPath2, "mp3", 2_000L, 2_000L),
                                        ),
                                    resumePosition = null,
                                ),
                            ),
                    )
                val fakeFactory = FakePlaybackRpcFactory(fakePlaybackService)

                val downloadService: DownloadService = mock()
                // Both files NOT downloaded → streaming path
                everySuspend { downloadService.getLocalPath(any()) } returns null
                everySuspend { downloadService.wasExplicitlyDeleted(any()) } returns false
                everySuspend { downloadService.downloadBook(any()) } returns
                    AppResult
                        .Success(DownloadOutcome.AlreadyDownloaded)

                val preparer = buildPreparer(downloadService, fakeFactory)
                val result = preparer.prepare(bookId)

                result.shouldNotBeNull()
                fakePlaybackService.prepareCallCount shouldBe 1

                // Each URL must be absolute: starts with serverUrl
                val url0 = result.timeline.files[0].streamingUrl
                val url1 = result.timeline.files[1].streamingUrl

                url0 shouldBe "$serverUrl$signedPath1"
                url1 shouldBe "$serverUrl$signedPath2"

                // Must end with the signed path fragment containing the audio route
                url0 shouldEndWith signedPath1
                url1 shouldEndWith signedPath2

                // Must NOT contain the legacy path
                url0 shouldNotContain "/api/v1/books/"
                url1 shouldNotContain "/api/v1/books/"

                // Must contain the new audio path segment
                url0 shouldContain "/api/v1/audio/"
                url1 shouldContain "/api/v1/audio/"
            }
        }

        // ── test 3: prepare fails + not downloaded → null ──────────────────────────────

        test("prepare fails and files not downloaded — prepare() returns null") {
            runTest {
                val fakePlaybackService =
                    FakePlaybackService(
                        prepareResult = AppResult.Failure(InternalError(debugInfo = "server error")),
                    )
                val fakeFactory = FakePlaybackRpcFactory(fakePlaybackService)

                val downloadService: DownloadService = mock()
                everySuspend { downloadService.getLocalPath(any()) } returns null
                everySuspend { downloadService.wasExplicitlyDeleted(any()) } returns false

                val preparer = buildPreparer(downloadService, fakeFactory)
                val result = preparer.prepare(bookId)

                result.shouldBeNull()
                // The RPC path was definitely reached — null is because prepare() returned Failure,
                // not because the book was missing.
                fakePlaybackService.prepareCallCount shouldBe 1
            }
        }

        // ── test 4: streaming-prepare RPC THROWS + not downloaded → null (never propagate) ──────
        test("prepare RPC throws and files not downloaded — prepare() returns null, does not propagate") {
            runTest {
                val fakePlaybackService =
                    FakePlaybackService(
                        prepareResult = AppResult.Failure(InternalError(debugInfo = "unused")),
                        prepareThrows = RuntimeException("RPC transport failure (e.g. dead WebSocket)"),
                    )
                val fakeFactory = FakePlaybackRpcFactory(fakePlaybackService)

                val downloadService: DownloadService = mock()
                everySuspend { downloadService.getLocalPath(any()) } returns null
                everySuspend { downloadService.wasExplicitlyDeleted(any()) } returns false

                val preparer = buildPreparer(downloadService, fakeFactory)

                // The streaming-prepare RPC throws a transport-level exception (not an AppResult.Failure)
                // — exactly the "started book A (downloaded), played book B (streaming), it failed" case.
                // prepare() must fold it to null per its contract, NOT let it escape across the Swift
                // Export seam as an opaque KotlinError. Pre-fix this line threw; post-fix it returns null.
                val result = preparer.prepare(bookId)

                result.shouldBeNull()
                fakePlaybackService.prepareCallCount shouldBe 1
            }
        }
    })

// ── Test doubles ──────────────────────────────────────────────────────────────────────────────

private val stubFailure = AppResult.Failure(InternalError(debugInfo = "stub"))

/**
 * Fake [PlaybackService] that returns a pre-configured result for [prepare] and records
 * how many times it was called. All other methods return [AppResult.Failure].
 */
private class FakePlaybackService(
    private val prepareResult: AppResult<ContractPreparedPlayback>,
    private val prepareThrows: Throwable? = null,
) : PlaybackService {
    var prepareCallCount = 0
        private set

    override suspend fun prepare(bookId: BookId): AppResult<ContractPreparedPlayback> {
        prepareCallCount++
        prepareThrows?.let { throw it }
        return prepareResult
    }

    override suspend fun getPosition(bookId: BookId): AppResult<PlaybackPositionSyncPayload?> = stubFailure

    override suspend fun recordPosition(
        request: RecordPositionRequest,
    ): AppResult<PlaybackPositionSyncPayload> = stubFailure

    override suspend fun getStats(): AppResult<UserStatsSyncPayload?> = stubFailure

    override suspend fun recordListeningEvent(
        request: RecordListeningEventRequest,
    ): AppResult<ListeningEventSyncPayload> = stubFailure
}

/**
 * Fake [PlaybackRpcFactory] that returns a fixed [FakePlaybackService] without any I/O.
 */
private class FakePlaybackRpcFactory(
    private val service: PlaybackService,
) : PlaybackRpcFactory {
    override suspend fun playbackService(): PlaybackService = service

    override suspend fun invalidate() = Unit
}
