package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.BookService
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
import dev.mokkery.verify.VerifyMode
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest

/**
 * Covers [PlaybackPreparer]'s `triggerBackgroundDownloadIfNeeded` combined gate — the fix for a
 * newly-reachable bug (binding real playback on web made `prepareForPlayback` run on every browser
 * tap for the first time): a coarse-pointer mobile browser reports `DeviceContext.supportsDownloads
 * == true` (it classifies as `Phone`/`Tablet`, which as a FORM FACTOR does support downloads) even
 * though its `DownloadService` (`NoDownloadsService`) can never actually finish one. Before this
 * fix, only device form factor gated the trigger, so every such tap fired a `downloadBook()` call
 * that was guaranteed to fail.
 *
 * Split out of [PlaybackPreparerTest] — which already covers the resume-position, auto-rewind,
 * and offline-first paths this class does not repeat — purely to keep that class under detekt's
 * LargeClass budget, the same reason [PlaybackPreparerHlsTest] was split out. No behavioral
 * reason for the split.
 */
class PlaybackPreparerBackgroundDownloadGateTest :
    FunSpec({

        val db: ListenUpDatabase = createInMemoryTestDatabase()

        afterSpec { db.close() }

        val bookId = BookId("book-prep-dl-gate-1")
        val serverUrl = "https://server.test"
        val audioFile1 = "af-prep-dl-gate-1"
        val audioFile2 = "af-prep-dl-gate-2"

        suspend fun seedBookAndAudioFiles() {
            db.bookDao().upsert(
                BookEntity(
                    id = bookId,
                    libraryId = LibraryId("lib-1"),
                    folderId = FolderId("folder-1"),
                    title = "Background Download Gate Test Book",
                    sortTitle = "Background Download Gate Test Book",
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

        // Both files NOT downloaded → prepare() streams, which is the only path that reaches
        // triggerBackgroundDownloadIfNeeded's non-trivial branch.
        val prepareRepository: PlaybackPrepareRepository =
            FakeGateTestPlaybackPrepareRepository(
                FakeGateTestPlaybackService(
                    prepareResult =
                        AppResult.Success(
                            ContractPreparedPlayback(
                                bookId = bookId.value,
                                audioFiles =
                                    listOf(
                                        PreparedAudioFile(audioFile1, 0, "/api/v1/audio/x/$audioFile1?sig=a", "mp3", 1_000L, 1_000L),
                                        PreparedAudioFile(audioFile2, 1, "/api/v1/audio/x/$audioFile2?sig=b", "mp3", 2_000L, 2_000L),
                                    ),
                                resumePosition = null,
                            ),
                        ),
                ),
            )

        // Device B: a coarse-pointer mobile browser classifies as DeviceType.Phone, same as a real
        // phone — that IS the bug: form factor alone must not be trusted to imply downloadability.
        fun buildPreparer(downloadService: DownloadService): PlaybackPreparer {
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

        test(
            "a device that supports downloads but a backend that cannot (e.g. a browser) " +
                "never triggers a doomed background download",
        ) {
            runTest {
                val downloadService: DownloadService = mock()
                every { downloadService.supportsDownloads } returns false
                everySuspend { downloadService.getLocalPath(any()) } returns null
                everySuspend { downloadService.wasExplicitlyDeleted(any()) } returns false

                val preparer = buildPreparer(downloadService)

                preparer.prepare(bookId).shouldNotBeNull()

                // The gate is checked synchronously, before the fire-and-forget scope.launch that
                // would otherwise call downloadBook() — so no dispatcher advance is needed to prove
                // it was never even attempted.
                verifySuspend(VerifyMode.not) { downloadService.downloadBook(any()) }
            }
        }

        test("a device and backend that both support downloads DOES trigger the background download") {
            runTest {
                val downloadService: DownloadService = mock()
                every { downloadService.supportsDownloads } returns true
                everySuspend { downloadService.getLocalPath(any()) } returns null
                everySuspend { downloadService.wasExplicitlyDeleted(any()) } returns false
                everySuspend { downloadService.downloadBook(any()) } returns
                    AppResult.Success(DownloadOutcome.AlreadyDownloaded)

                val preparer = buildPreparer(downloadService)

                preparer.prepare(bookId).shouldNotBeNull()

                verifySuspend(VerifyMode.exactly(1)) { downloadService.downloadBook(any()) }
            }
        }
    })

private val stubFailure = AppResult.Failure(InternalError(debugInfo = "unused"))

/** Fake [PlaybackService] that returns a pre-configured result for [prepare]. */
private class FakeGateTestPlaybackService(
    private val prepareResult: AppResult<ContractPreparedPlayback>,
) : PlaybackService {
    override suspend fun prepare(
        bookId: BookId,
        capabilities: Set<CodecCapability>?,
        forceTranscode: Boolean,
    ): AppResult<ContractPreparedPlayback> = prepareResult

    override suspend fun getPosition(bookId: BookId): AppResult<PlaybackPositionSyncPayload?> = stubFailure

    override suspend fun recordPosition(
        request: RecordPositionRequest,
    ): AppResult<PlaybackPositionSyncPayload> = stubFailure

    override suspend fun getStats(): AppResult<UserStatsSyncPayload?> = stubFailure

    override suspend fun recordListeningEvent(
        request: RecordListeningEventRequest,
    ): AppResult<ListeningEventSyncPayload> = stubFailure
}

/** Fake [PlaybackPrepareRepository] that delegates to a fixed [FakeGateTestPlaybackService] without any I/O. */
private class FakeGateTestPlaybackPrepareRepository(
    private val service: PlaybackService,
) : PlaybackPrepareRepository {
    override suspend fun prepare(
        bookId: BookId,
        forceTranscode: Boolean,
    ): AppResult<ContractPreparedPlayback> = service.prepare(bookId, forceTranscode = forceTranscode)

    override suspend fun getPosition(bookId: BookId): AppResult<PlaybackPositionSyncPayload?> = service.getPosition(bookId)
}
