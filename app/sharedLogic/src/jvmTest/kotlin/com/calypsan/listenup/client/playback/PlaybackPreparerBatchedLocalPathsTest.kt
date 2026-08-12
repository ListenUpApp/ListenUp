package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.BookService
import com.calypsan.listenup.api.dto.PreparedAudioFile
import com.calypsan.listenup.api.dto.PreparedPlayback as ContractPreparedPlayback
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
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest

/**
 * PERF-13: [PlaybackPreparer.buildTimeline] resolves local paths for a book's audio files via one
 * batched [DownloadService.getLocalPaths] call instead of one [DownloadService.getLocalPath] Room
 * query per file. A sibling of [PlaybackPreparerTest] (split out per Detekt's `LargeClass`), with
 * its own minimal fixture — [PlaybackPreparerTest]'s `buildPreparer`/`FakePlaybackService` helpers
 * are local to its `FunSpec` lambda / file-private, so unreachable from a sibling file.
 */
class PlaybackPreparerBatchedLocalPathsTest :
    FunSpec({

        val db: ListenUpDatabase = createInMemoryTestDatabase()
        afterSpec { db.close() }

        val bookId = BookId("book-batched-1")
        val serverUrl = "https://server.test"
        val audioFile1 = "af-batched-1"
        val audioFile2 = "af-batched-2"

        beforeTest {
            db.audioFileDao().deleteForBook(bookId.value)
            db.bookDao().deleteById(bookId)
            db.bookDao().upsert(
                BookEntity(
                    id = bookId,
                    libraryId = LibraryId("lib-1"),
                    folderId = FolderId("folder-1"),
                    title = "Batched Local Paths Test Book",
                    sortTitle = "Batched Local Paths Test Book",
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
            prepareRepository: PlaybackPrepareRepository,
        ): PlaybackPreparer {
            val localPreferences: LocalPreferences = mock()
            every { localPreferences.autoRewindEnabled } returns kotlinx.coroutines.flow.MutableStateFlow(false)

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

        test("multi-file book resolves local paths via one batched call, not one getLocalPath per file") {
            runTest {
                val prepareRepository: PlaybackPrepareRepository = mock()
                everySuspend { prepareRepository.prepare(any()) } returns
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
                    )
                everySuspend { prepareRepository.getPosition(any()) } returns AppResult.Success(null)

                val downloadService: DownloadService = mock()
                everySuspend { downloadService.getLocalPath(any()) } returns null
                everySuspend { downloadService.getLocalPaths(any()) } returns
                    mapOf(audioFile1 to "/local/af-batched-1.mp3", audioFile2 to "/local/af-batched-2.mp3")
                everySuspend { downloadService.wasExplicitlyDeleted(any()) } returns false
                everySuspend { downloadService.downloadBook(any()) } returns
                    AppResult.Success(DownloadOutcome.AlreadyDownloaded)

                val preparer = buildPreparer(downloadService, prepareRepository)
                val result = preparer.prepare(bookId)

                result.shouldNotBeNull()
                // Resolves the same paths a per-file getLocalPath loop would have — just via one call.
                result.timeline.files[0].localPath shouldBe "/local/af-batched-1.mp3"
                result.timeline.files[1].localPath shouldBe "/local/af-batched-2.mp3"
                // Both files resolved locally, so streaming prepare() is never invoked.
                result.timeline.files[0].streamingUrl shouldBe ""
                result.timeline.files[1].streamingUrl shouldBe ""

                // The N+1 is actually gone: one batched call carrying every id, zero per-file calls.
                verifySuspend(VerifyMode.exactly(1)) { downloadService.getLocalPaths(listOf(audioFile1, audioFile2)) }
                verifySuspend(VerifyMode.exactly(0)) { downloadService.getLocalPath(any()) }
            }
        }
    })
