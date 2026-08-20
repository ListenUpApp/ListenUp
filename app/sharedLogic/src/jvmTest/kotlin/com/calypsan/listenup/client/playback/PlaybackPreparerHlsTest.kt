package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.PlaybackService
import com.calypsan.listenup.api.dto.CodecCapability
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
import com.calypsan.listenup.client.data.sync.SyncDomainHandler
import com.calypsan.listenup.client.device.DeviceContext
import com.calypsan.listenup.client.device.DeviceType
import com.calypsan.listenup.client.domain.model.DownloadOutcome
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.LocalPreferences
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest

/**
 * Covers [PlaybackPreparer]'s `buildTimeline` HLS-carriage logic: the `serverUrl +` prefix
 * applied to a transcoded file's playlist url, the `mapNotNull` that must leave a direct-play
 * file's [PlaybackTimeline.FileSegment.hlsUrl] null rather than an empty-prefixed string, and
 * both branches where the internal per-file URL maps stay default-empty (fully downloaded;
 * `prepare()` failed offline with a partial download).
 *
 * Split out of [PlaybackPreparerTest] — which already covers the resume-position, auto-rewind,
 * and offline-first paths this class does not repeat — purely to keep that class under detekt's
 * LargeClass budget. No behavioral reason for the split.
 */
class PlaybackPreparerHlsTest :
    FunSpec({

        val db: ListenUpDatabase = createInMemoryTestDatabase()

        afterSpec { db.close() }

        val bookId = BookId("book-prep-hls-1")
        val serverUrl = "https://server.test"
        val audioFile1 = "af-prep-hls-1"
        val audioFile2 = "af-prep-hls-2"

        suspend fun seedBookAndAudioFiles() {
            db.bookDao().upsert(
                BookEntity(
                    id = bookId,
                    libraryId = LibraryId("lib-1"),
                    folderId = FolderId("folder-1"),
                    title = "Prepare HLS Test Book",
                    sortTitle = "Prepare HLS Test Book",
                    subtitle = null,
                    coverHash = null,
                    totalDuration = 3_000L,
                    description = null,
                    publishYear = null,
                    publisher = null,
                    language = null,
                    isbn = null,
                    asin = null,
                    abridged = false,
                    normalizationGainDb = null,
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

        beforeTest {
            db.audioFileDao().deleteForBook(bookId.value)
            db.bookDao().deleteById(bookId)
            seedBookAndAudioFiles()
        }

        fun buildPreparer(
            downloadService: DownloadService,
            prepareRepository: PlaybackPrepareRepository,
        ): PlaybackPreparer {
            val localPreferences: LocalPreferences = mock()
            every { localPreferences.autoRewindEnabled } returns MutableStateFlow(false)

            val tokenProvider: AudioTokenProvider = mock()
            everySuspend { tokenProvider.prepareForPlayback() } returns Unit

            val serverConfig: ServerConfig = mock()
            everySuspend { serverConfig.getActiveUrl() } returns ServerUrl(serverUrl)

            val imageStorage: ImageStorage = mock()
            every { imageStorage.exists(any()) } returns false

            val playbackPreferences: PlaybackPreferences = mock()
            everySuspend { playbackPreferences.getDefaultPlaybackSpeed() } returns 1.0f
            everySuspend { playbackPreferences.getDefaultVolumeBoostDb() } returns 0f

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
                prepareRepository = prepareRepository,
                channel = RpcChannel.forTest(mock<BookService>()),
                scope = CoroutineScope(Job()),
                bookSyncDomainHandler = mock<SyncDomainHandler<BookSyncPayload>>(),
                localPreferences = localPreferences,
            )
        }

        // Both files NOT downloaded → prepare() is called (the only path that can ever carry hls).
        fun streamingDownloadService(): DownloadService {
            val downloadService: DownloadService = mock()
            every { downloadService.supportsDownloads } returns true
            everySuspend { downloadService.getLocalPath(any()) } returns null
            everySuspend { downloadService.wasExplicitlyDeleted(any()) } returns false
            everySuspend { downloadService.downloadBook(any()) } returns
                AppResult.Success(DownloadOutcome.AlreadyDownloaded)
            return downloadService
        }

        // Every file has a local path → prepare() is skipped entirely (offline-first).
        fun downloadedDownloadService(): DownloadService {
            val downloadService: DownloadService = mock()
            every { downloadService.supportsDownloads } returns true
            everySuspend { downloadService.getLocalPath(audioFile1) } returns "/local/af-prep-hls-1.mp3"
            everySuspend { downloadService.getLocalPath(audioFile2) } returns "/local/af-prep-hls-2.mp3"
            everySuspend { downloadService.wasExplicitlyDeleted(any()) } returns false
            everySuspend { downloadService.downloadBook(any()) } returns
                AppResult.Success(DownloadOutcome.AlreadyDownloaded)
            return downloadService
        }

        // hlsUrl1/hlsUrl2 default null (no transcode) — pass one to model the server deciding a
        // given file needs a transcode.
        fun preparedWith(
            hlsUrl1: String? = null,
            hlsUrl2: String? = null,
        ): PlaybackPrepareRepository =
            FakeHlsPlaybackPrepareRepository(
                FakeHlsPlaybackService(
                    prepareResult =
                        AppResult.Success(
                            ContractPreparedPlayback(
                                bookId = bookId.value,
                                audioFiles =
                                    listOf(
                                        PreparedAudioFile(
                                            audioFile1,
                                            0,
                                            "/api/v1/audio/x/$audioFile1?sig=a",
                                            "mp3",
                                            1_000L,
                                            1_000L,
                                            hlsUrl1,
                                        ),
                                        PreparedAudioFile(
                                            audioFile2,
                                            1,
                                            "/api/v1/audio/x/$audioFile2?sig=b",
                                            "mp3",
                                            2_000L,
                                            2_000L,
                                            hlsUrl2,
                                        ),
                                    ),
                                resumePosition = null,
                            ),
                        ),
                ),
            )

        test("a transcoded file's hls url is server-prefixed; a direct-play file gets none") {
            runTest {
                val preparer =
                    buildPreparer(
                        downloadService = streamingDownloadService(),
                        prepareRepository = preparedWith(hlsUrl1 = "/api/v1/hls/x/$audioFile1/master.m3u8?sig=a"),
                    )

                val result = preparer.prepare(bookId)

                result.shouldNotBeNull()
                // The one file the server decided needs a transcode gets an absolute, server-prefixed
                // HLS url — not a raw relative path, and not the empty streamingUrl prefixed instead.
                result.timeline.files[0].hlsUrl shouldBe "$serverUrl/api/v1/hls/x/$audioFile1/master.m3u8?sig=a"
                // The other file's response carried no hlsUrl at all: mapNotNull must produce NO map
                // entry for it — this must be null, not "$serverUrl" or "$serverUrl" + "".
                result.timeline.files[1]
                    .hlsUrl
                    .shouldBeNull()
            }
        }

        test("fully downloaded — hls url is never populated; prepare() is never called") {
            runTest {
                val fakePlaybackService =
                    FakeHlsPlaybackService(prepareResult = AppResult.Failure(InternalError(debugInfo = "unused")))
                val preparer =
                    buildPreparer(
                        downloadService = downloadedDownloadService(),
                        prepareRepository = FakeHlsPlaybackPrepareRepository(fakePlaybackService),
                    )

                val result = preparer.prepare(bookId)

                result.shouldNotBeNull()
                fakePlaybackService.prepareCallCount shouldBe 0 // no hlsUrl could ever arrive
                result.timeline.files[0]
                    .hlsUrl
                    .shouldBeNull()
                result.timeline.files[1]
                    .hlsUrl
                    .shouldBeNull()
            }
        }

        test("partial download offline — prepare() fails; hls stays empty alongside the empty streaming urls") {
            runTest {
                val fakePlaybackService =
                    FakeHlsPlaybackService(prepareResult = AppResult.Failure(InternalError(debugInfo = "offline")))

                val downloadService: DownloadService = mock()
                every { downloadService.supportsDownloads } returns true
                everySuspend { downloadService.getLocalPath(audioFile1) } returns "/local/af-prep-hls-1.mp3"
                everySuspend { downloadService.getLocalPath(audioFile2) } returns null // missing
                everySuspend { downloadService.wasExplicitlyDeleted(any()) } returns false
                everySuspend { downloadService.downloadBook(any()) } returns
                    AppResult.Success(DownloadOutcome.AlreadyDownloaded)

                val preparer =
                    buildPreparer(downloadService, FakeHlsPlaybackPrepareRepository(fakePlaybackService))
                val result = preparer.prepare(bookId)

                result.shouldNotBeNull()
                fakePlaybackService.prepareCallCount shouldBe 1
                // The failed prepare() call means the internal per-file URL maps defaulted empty for
                // BOTH — the downloaded file plays from disk, the missing one has an honest gap, and
                // neither has HLS.
                result.timeline.files[0]
                    .hlsUrl
                    .shouldBeNull()
                result.timeline.files[1]
                    .hlsUrl
                    .shouldBeNull()
            }
        }
    })

// ── Test doubles ──────────────────────────────────────────────────────────────────────────────

private val stubFailure = AppResult.Failure(InternalError(debugInfo = "unused"))

/**
 * Fake [PlaybackService] that returns a pre-configured result for [prepare] and records how many
 * times it was called. All other methods return [stubFailure]. Distinct from
 * [PlaybackPreparerTest]'s private `FakePlaybackService` only by name (both files are in the same
 * package) — kept separate rather than shared to avoid coupling two independent spec files.
 */
private class FakeHlsPlaybackService(
    private val prepareResult: AppResult<ContractPreparedPlayback>,
) : PlaybackService {
    var prepareCallCount = 0
        private set

    override suspend fun prepare(
        bookId: BookId,
        capabilities: Set<CodecCapability>?,
        forceTranscode: Boolean,
    ): AppResult<ContractPreparedPlayback> {
        prepareCallCount++
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

/** Fake [PlaybackPrepareRepository] that delegates to a fixed [FakeHlsPlaybackService] without any I/O. */
private class FakeHlsPlaybackPrepareRepository(
    private val service: PlaybackService,
) : PlaybackPrepareRepository {
    override suspend fun prepare(
        bookId: BookId,
        forceTranscode: Boolean,
    ): AppResult<ContractPreparedPlayback> = service.prepare(bookId, forceTranscode = forceTranscode)

    override suspend fun getPosition(bookId: BookId): AppResult<PlaybackPositionSyncPayload?> = service.getPosition(bookId)
}
