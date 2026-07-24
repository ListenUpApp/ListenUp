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
import com.calypsan.listenup.api.BookService
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.device.DeviceContext
import com.calypsan.listenup.client.device.DeviceType
import com.calypsan.listenup.client.domain.model.DownloadOutcome
import com.calypsan.listenup.client.domain.model.PlaybackPosition
import com.calypsan.listenup.client.data.sync.SyncDomainHandler
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.PlaybackPositionRepository
import com.calypsan.listenup.client.domain.repository.PlaybackPreferences
import com.calypsan.listenup.client.domain.repository.PlaybackPrepareRepository
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
                    coverHash = "cover-hash-prep",
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
            prepareRepository: PlaybackPrepareRepository,
            progressTracker: ProgressTracker = buildProgressTracker(),
        ): PlaybackPreparer {
            val tokenProvider: AudioTokenProvider = mock()
            everySuspend { tokenProvider.prepareForPlayback() } returns Unit

            val serverConfig: ServerConfig = mock()
            everySuspend { serverConfig.getActiveUrl() } returns ServerUrl(serverUrl)

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
                progressTracker = progressTracker,
                tokenProvider = tokenProvider,
                deviceContext = DeviceContext(type = DeviceType.Phone),
                downloadService = downloadService,
                prepareRepository = prepareRepository,
                channel = RpcChannel.forTest(mock<BookService>()),
                scope = CoroutineScope(Job()),
                bookSyncDomainHandler = mock<SyncDomainHandler<BookSyncPayload>>(),
            )
        }

        // Builds a ProgressTracker whose getResumePosition() returns [local], modelling a
        // stale (or fresh) local Room row at resume time.
        fun trackerWithLocalPosition(local: PlaybackPosition?): ProgressTracker {
            val repo: PlaybackPositionRepository = mock()
            everySuspend { repo.savePlaybackState(any(), any()) } returns AppResult.Success(Unit)
            everySuspend { repo.get(any<BookId>()) } returns AppResult.Success(local)
            return buildProgressTracker(positionRepository = repo)
        }

        // A streaming-path download service: no file has a local path, so prepare() is called
        // and its server-authoritative resumePosition participates in the merge.
        fun streamingDownloadService(): DownloadService {
            val downloadService: DownloadService = mock()
            everySuspend { downloadService.getLocalPath(any()) } returns null
            everySuspend { downloadService.wasExplicitlyDeleted(any()) } returns false
            everySuspend { downloadService.downloadBook(any()) } returns
                AppResult.Success(DownloadOutcome.AlreadyDownloaded)
            return downloadService
        }

        // Wraps a server resumePosition into a Success(PreparedPlayback) with the two seeded files.
        fun preparedWith(resumePosition: PlaybackPositionSyncPayload?): PlaybackPrepareRepository =
            FakePlaybackPrepareRepository(
                FakePlaybackService(
                    prepareResult =
                        AppResult.Success(
                            ContractPreparedPlayback(
                                bookId = bookId.value,
                                audioFiles =
                                    listOf(
                                        PreparedAudioFile(audioFile1, 0, "/api/v1/audio/x/$audioFile1?sig=a", "mp3", 1_000L, 1_000L),
                                        PreparedAudioFile(audioFile2, 1, "/api/v1/audio/x/$audioFile2?sig=b", "mp3", 2_000L, 2_000L),
                                    ),
                                resumePosition = resumePosition,
                            ),
                        ),
                ),
            )

        // A fully-downloaded service: every file has a local path → prepare() is skipped and the
        // authoritative position must instead come from the best-effort getPosition() call (B8).
        fun downloadedDownloadService(): DownloadService {
            val downloadService: DownloadService = mock()
            everySuspend { downloadService.getLocalPath(audioFile1) } returns "/local/af-prep-1.mp3"
            everySuspend { downloadService.getLocalPath(audioFile2) } returns "/local/af-prep-2.mp3"
            everySuspend { downloadService.wasExplicitlyDeleted(any()) } returns false
            everySuspend { downloadService.downloadBook(any()) } returns
                AppResult.Success(DownloadOutcome.AlreadyDownloaded)
            return downloadService
        }

        // Builds a prepare repository whose getPosition() returns [result]; prepare() would fail if
        // called — which it must NOT be on the fully-downloaded path.
        fun downloadedWithServerPosition(
            result: AppResult<PlaybackPositionSyncPayload?>,
        ): Pair<PlaybackPrepareRepository, FakePlaybackService> {
            val svc = FakePlaybackService(prepareResult = stubFailure, getPositionResult = result)
            return FakePlaybackPrepareRepository(svc) to svc
        }

        // ── resume-position merge (server-authoritative newer-wins) ─────────────────────

        // Concrete epoch anchors. 4:30:00 = 16_200_000ms, 6:00:00 = 21_600_000ms.
        val baseTime = 1_000_000_000_000L
        val laterTime = baseTime + 3_600_000L // +1h
        val pos430 = 16_200_000L
        val pos600 = 21_600_000L

        fun localPosition(
            positionMs: Long,
            lastPlayedAtMs: Long,
            isFinished: Boolean = false,
        ): PlaybackPosition =
            PlaybackPosition(
                bookId = bookId.value,
                positionMs = positionMs,
                playbackSpeed = 1.0f,
                hasCustomSpeed = false,
                updatedAtMs = lastPlayedAtMs,
                syncedAtMs = null,
                lastPlayedAtMs = lastPlayedAtMs,
                isFinished = isFinished,
            )

        fun serverPosition(
            positionMs: Long,
            lastPlayedAt: Long,
            finished: Boolean = false,
        ): PlaybackPositionSyncPayload =
            PlaybackPositionSyncPayload(
                id = "pos-server",
                bookId = bookId.value,
                positionMs = positionMs,
                lastPlayedAt = lastPlayedAt,
                finished = finished,
                playbackSpeed = 1.0f,
                currentChapterId = null,
                revision = 1L,
                updatedAt = lastPlayedAt,
                createdAt = baseTime,
                deletedAt = null,
            )

        test("server position is newer — prepared resume resolves to the server position, not the stale local row") {
            runTest {
                // Device B: local Room row is stale (4:30:00 @ baseTime); the server's prepare()
                // carries the authoritative newer position (6:00:00 @ +1h) from device A.
                val preparer =
                    buildPreparer(
                        downloadService = streamingDownloadService(),
                        prepareRepository = preparedWith(serverPosition(pos600, laterTime)),
                        progressTracker = trackerWithLocalPosition(localPosition(pos430, baseTime)),
                    )

                val result = preparer.prepare(bookId)

                result.shouldNotBeNull()
                // Pre-fix this is 16_200_000 (the stale local row) — the crown-jewel data-loss bug.
                result.resumePositionMs shouldBe pos600
            }
        }

        test("local position is newer — prepared resume resolves to the local row, not the stale server position") {
            runTest {
                val preparer =
                    buildPreparer(
                        downloadService = streamingDownloadService(),
                        prepareRepository = preparedWith(serverPosition(pos430, baseTime)),
                        progressTracker = trackerWithLocalPosition(localPosition(pos600, laterTime)),
                    )

                val result = preparer.prepare(bookId)

                result.shouldNotBeNull()
                result.resumePositionMs shouldBe pos600
            }
        }

        test("winning position is finished — resume starts at 0 for re-read") {
            runTest {
                // Server wins (newer) AND is finished → re-read from the beginning.
                val preparer =
                    buildPreparer(
                        downloadService = streamingDownloadService(),
                        prepareRepository = preparedWith(serverPosition(pos600, laterTime, finished = true)),
                        progressTracker = trackerWithLocalPosition(localPosition(pos430, baseTime)),
                    )

                val result = preparer.prepare(bookId)

                result.shouldNotBeNull()
                result.resumePositionMs shouldBe 0L
            }
        }

        test("server resume position is null — falls back to the local row unchanged") {
            runTest {
                val preparer =
                    buildPreparer(
                        downloadService = streamingDownloadService(),
                        prepareRepository = preparedWith(resumePosition = null),
                        progressTracker = trackerWithLocalPosition(localPosition(pos430, baseTime)),
                    )

                val result = preparer.prepare(bookId)

                result.shouldNotBeNull()
                result.resumePositionMs shouldBe pos430
            }
        }

        // ── B8: fully-downloaded path also reconciles the server-authoritative position ──

        test("fully downloaded — server getPosition is newer, resume uses it (B8 offline clobber fix)") {
            runTest {
                // Phone has the book downloaded and a stale local row (4:30 @ base); the server has
                // the tablet's newer progress (6:00 @ +1h). prepare() is skipped (downloaded), so the
                // authoritative position must arrive via getPosition() and win the merge.
                val (prepareRepo, svc) =
                    downloadedWithServerPosition(AppResult.Success(serverPosition(pos600, laterTime)))
                val preparer =
                    buildPreparer(
                        downloadService = downloadedDownloadService(),
                        prepareRepository = prepareRepo,
                        progressTracker = trackerWithLocalPosition(localPosition(pos430, baseTime)),
                    )

                val result = preparer.prepare(bookId)

                result.shouldNotBeNull()
                svc.prepareCallCount shouldBe 0 // offline-first: never touches prepare() when downloaded
                svc.getPositionCallCount shouldBe 1 // ...but DOES fetch the authoritative position
                // Pre-fix: resolves to the stale local 4:30 and clobbers the tablet — the F1 gap for downloads.
                result.resumePositionMs shouldBe pos600
            }
        }

        test("fully downloaded — getPosition fails offline, resume falls back to the local row") {
            runTest {
                val (prepareRepo, svc) = downloadedWithServerPosition(stubFailure)
                val preparer =
                    buildPreparer(
                        downloadService = downloadedDownloadService(),
                        prepareRepository = prepareRepo,
                        progressTracker = trackerWithLocalPosition(localPosition(pos430, baseTime)),
                    )

                val result = preparer.prepare(bookId)

                result.shouldNotBeNull()
                svc.prepareCallCount shouldBe 0
                // getPosition failed (offline) → never-stranded: resume resolves from Room, unchanged.
                result.resumePositionMs shouldBe pos430
            }
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
                val fakeFactory = FakePlaybackPrepareRepository(fakePlaybackService)

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

                // The prepared value carries the book's content hash so the player surfaces
                // (mini player, now-playing, full screen) content-address the cover and refresh
                // it after a re-scrape instead of serving the stale id-stable local file.
                result.coverHash shouldBe "cover-hash-prep"

                result.timeline.files[0].localPath shouldBe "/local/af-prep-1.mp3"
                result.timeline.files[1].localPath shouldBe "/local/af-prep-2.mp3"
                // Streaming URL is empty when fully downloaded
                result.timeline.files[0].streamingUrl shouldBe ""
                result.timeline.files[1].streamingUrl shouldBe ""
            }
        }

        // ── B3: partial download, offline — downloaded files still play (never-stranded) ──

        test("partial download offline — prepare() fails but downloaded files still play") {
            runTest {
                // prepare() fails (offline / dead socket) and only file 1 is downloaded.
                val fakePlaybackService =
                    FakePlaybackService(
                        prepareResult = AppResult.Failure(InternalError(debugInfo = "offline")),
                    )
                val fakeFactory = FakePlaybackPrepareRepository(fakePlaybackService)

                val downloadService: DownloadService = mock()
                everySuspend { downloadService.getLocalPath(audioFile1) } returns "/local/af-prep-1.mp3"
                everySuspend { downloadService.getLocalPath(audioFile2) } returns null // missing
                everySuspend { downloadService.wasExplicitlyDeleted(any()) } returns false
                everySuspend { downloadService.downloadBook(any()) } returns
                    AppResult.Success(DownloadOutcome.AlreadyDownloaded)

                val preparer = buildPreparer(downloadService, fakeFactory)
                val result = preparer.prepare(bookId)

                // Pre-fix: buildTimeline returned null on the prepare() Failure → whole book unplayable.
                result.shouldNotBeNull()
                // prepare() WAS attempted (some files missing) but failed offline.
                fakePlaybackService.prepareCallCount shouldBe 1
                // Downloaded file plays from disk...
                result.timeline.files[0].localPath shouldBe "/local/af-prep-1.mp3"
                result.timeline.files[0].playbackUri shouldBe "file:///local/af-prep-1.mp3"
                // ...and the missing file has NO fabricated streaming URL (honest gap, not a blanket
                // streaming fallback that fails the whole book).
                result.timeline.files[1].localPath shouldBe null
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
                val fakeFactory = FakePlaybackPrepareRepository(fakePlaybackService)

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
                val fakeFactory = FakePlaybackPrepareRepository(fakePlaybackService)

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
                val fakeFactory = FakePlaybackPrepareRepository(fakePlaybackService)

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
    private val getPositionResult: AppResult<PlaybackPositionSyncPayload?> = stubFailure,
) : PlaybackService {
    var prepareCallCount = 0
        private set
    var getPositionCallCount = 0
        private set

    override suspend fun prepare(bookId: BookId): AppResult<ContractPreparedPlayback> {
        prepareCallCount++
        prepareThrows?.let { throw it }
        return prepareResult
    }

    override suspend fun getPosition(bookId: BookId): AppResult<PlaybackPositionSyncPayload?> {
        getPositionCallCount++
        return getPositionResult
    }

    override suspend fun recordPosition(
        request: RecordPositionRequest,
    ): AppResult<PlaybackPositionSyncPayload> = stubFailure

    override suspend fun getStats(): AppResult<UserStatsSyncPayload?> = stubFailure

    override suspend fun recordListeningEvent(
        request: RecordListeningEventRequest,
    ): AppResult<ListeningEventSyncPayload> = stubFailure
}

/**
 * Fake [PlaybackPrepareRepository] that delegates to a fixed [FakePlaybackService] without any I/O,
 * so `prepareCallCount` assertions still hold across the seam.
 */
private class FakePlaybackPrepareRepository(
    private val service: PlaybackService,
) : PlaybackPrepareRepository {
    override suspend fun prepare(bookId: BookId): AppResult<ContractPreparedPlayback> = service.prepare(bookId)

    override suspend fun getPosition(bookId: BookId): AppResult<PlaybackPositionSyncPayload?> = service.getPosition(bookId)
}
