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
 * Regression coverage for the 2026-08-13 tap-to-audio-latency fix: a fully-downloaded book must
 * never await an audio token, because it plays entirely from local file:// paths and issues no
 * HTTP request the token could authenticate — see [PlaybackPreparer.prepareInternal]'s reorder.
 *
 * Split out from [PlaybackPreparerTest] purely to keep both specs under detekt's LargeClass line
 * budget — no behavioral reason for the split.
 */
class PlaybackPreparerTokenBudgetTest :
    FunSpec({

        val db: ListenUpDatabase = createInMemoryTestDatabase()
        afterSpec { db.close() }

        val bookId = BookId("book-token-budget-1")
        val serverUrl = "https://server.test"
        val audioFile1 = "af-token-budget-1"
        val audioFile2 = "af-token-budget-2"

        beforeTest {
            db.audioFileDao().deleteForBook(bookId.value)
            db.bookDao().deleteById(bookId)
            db.bookDao().upsert(
                BookEntity(
                    id = bookId,
                    libraryId = LibraryId("lib-1"),
                    folderId = FolderId("folder-1"),
                    title = "Token Budget Test Book",
                    sortTitle = "Token Budget Test Book",
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

        fun buildPreparer(
            downloadService: DownloadService,
            tokenProvider: AudioTokenProvider,
        ): PlaybackPreparer {
            val localPreferences: LocalPreferences = mock()
            every { localPreferences.autoRewindEnabled } returns MutableStateFlow(false)

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
                prepareRepository = testPlaybackPrepareRepository(audioFile1, audioFile2, bookId = bookId.value),
                channel = RpcChannel.forTest(mock<BookService>()),
                scope = CoroutineScope(Job()),
                bookSyncDomainHandler = mock<SyncDomainHandler<BookSyncPayload>>(),
                localPreferences = localPreferences,
                nowMillis = { 1_000_000_000_000L },
            )
        }

        // Every file has a local path — buildTimeline skips prepare() entirely (offline-first).
        fun downloadedDownloadService(): DownloadService {
            val downloadService: DownloadService = mock()
            every { downloadService.supportsDownloads } returns true
            everySuspend { downloadService.getLocalPath(audioFile1) } returns "/local/af-token-budget-1.mp3"
            everySuspend { downloadService.getLocalPath(audioFile2) } returns "/local/af-token-budget-2.mp3"
            everySuspend { downloadService.wasExplicitlyDeleted(any()) } returns false
            everySuspend { downloadService.downloadBook(any()) } returns
                AppResult.Success(DownloadOutcome.AlreadyDownloaded)
            return downloadService
        }

        // No file has a local path — buildTimeline calls prepare() for signed streaming URLs.
        fun streamingDownloadService(): DownloadService {
            val downloadService: DownloadService = mock()
            every { downloadService.supportsDownloads } returns true
            everySuspend { downloadService.getLocalPath(any()) } returns null
            everySuspend { downloadService.wasExplicitlyDeleted(any()) } returns false
            everySuspend { downloadService.downloadBook(any()) } returns
                AppResult.Success(DownloadOutcome.AlreadyDownloaded)
            return downloadService
        }

        test("fully downloaded — tokenProvider.prepareForPlayback is never awaited") {
            runTest {
                val tokenProvider: AudioTokenProvider = mock()
                everySuspend { tokenProvider.prepareForPlayback() } returns Unit

                val preparer = buildPreparer(downloadedDownloadService(), tokenProvider)

                val result = preparer.prepare(bookId)

                result.shouldNotBeNull()
                // A fully-downloaded book plays entirely from local file:// paths — no HTTP audio
                // request is ever made, so no audio token is needed. Awaiting one anyway is the
                // 8.08s tap-to-audio stall reproduced on 2026-08-13 (a launch-time token refresh
                // still in flight when the tap lands).
                verifySuspend(VerifyMode.exactly(0)) { tokenProvider.prepareForPlayback() }
            }
        }

        test("streaming — tokenProvider.prepareForPlayback is still awaited before playback starts") {
            runTest {
                val tokenProvider: AudioTokenProvider = mock()
                everySuspend { tokenProvider.prepareForPlayback() } returns Unit

                val preparer = buildPreparer(streamingDownloadService(), tokenProvider)

                val result = preparer.prepare(bookId)

                result.shouldNotBeNull()
                // Streaming files are fetched over HTTP with a bearer token — the token must still
                // be fresh before playback starts.
                verifySuspend(VerifyMode.exactly(1)) { tokenProvider.prepareForPlayback() }
            }
        }
    })
