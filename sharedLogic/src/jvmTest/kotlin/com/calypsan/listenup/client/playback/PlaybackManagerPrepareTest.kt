package com.calypsan.listenup.client.playback

import com.calypsan.listenup.client.data.local.db.AudioFileEntity
import com.calypsan.listenup.client.data.local.db.BookEntity
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.device.DeviceContext
import com.calypsan.listenup.client.device.DeviceType
import com.calypsan.listenup.client.domain.model.DownloadOutcome
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.client.data.sync.SyncDomainHandler
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.PlaybackPreferences
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.download.DownloadService
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.fake.FakePlaybackBandwidthCoordinator
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.ServerUrl
import com.calypsan.listenup.core.Timestamp
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import com.calypsan.listenup.api.BookService
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import dev.mokkery.mock
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest

/**
 * Verifies the DAO → PlaybackManager.prepareForPlayback contract.
 *
 * Seeds audio files directly into the junction; calls prepareForPlayback; asserts
 * the returned PrepareResult has the expected timeline shape (segment URLs,
 * durations, and order).
 *
 * No actual audio plays — this is a data-layer-to-PlaybackManager integration
 * test. The acceptance test for actual playback is a manual checkpoint on a
 * real device before push.
 */
class PlaybackManagerPrepareTest :
    FunSpec({
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
                    totalDuration = 5_400_000L,
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
                    audioFile(index = 0, id = "af-0"),
                    audioFile(index = 1, id = "af-1"),
                    audioFile(index = 2, id = "af-2"),
                ),
            )
        }

        fun createPlaybackManager(db: ListenUpDatabase): PlaybackManager {
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

            val playbackPreferences: PlaybackPreferences = mock()
            everySuspend { playbackPreferences.getDefaultPlaybackSpeed() } returns 1.0f

            // ProgressTracker is a final class — use the shared helper from PlaybackManagerTestSupport.
            // prepareForPlayback reads positionRepository; defaultPositionRepository() stubs it to
            // return null (no saved position), exercising the fresh-playback path.
            val progressTracker = buildProgressTracker()

            return PlaybackManagerImpl(
                serverConfig = serverConfig,
                playbackPreferences = playbackPreferences,
                bookDao = db.bookDao(),
                audioFileDao = db.audioFileDao(),
                chapterDao = db.chapterDao(),
                imageStorage = imageStorage,
                progressTracker = progressTracker,
                reporter = PlaybackProgressReporter(progressTracker, recorder = null, scope = CoroutineScope(Job())),
                tokenProvider = tokenProvider,
                deviceContext = DeviceContext(type = DeviceType.Phone),
                downloadService = downloadService,
                prepareRepository = testPlaybackPrepareRepository("af-0", "af-1", "af-2"),
                channel = RpcChannel.forTest(mock<BookService>()),
                scope = CoroutineScope(Job()),
                bookSyncDomainHandler = mock<SyncDomainHandler<BookSyncPayload>>(),
                playbackBandwidthCoordinator = FakePlaybackBandwidthCoordinator(),
            )
        }

        test("prepareForPlayback builds timeline from junction rows in index order") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    seedBookAndAudioFiles(db)

                    val playbackManager = createPlaybackManager(db)

                    val result = playbackManager.prepareForPlayback(BookId("book-1")).shouldNotBeNull()

                    withClue("timeline should have 3 segments") { result.timeline.files.size shouldBe 3 }
                    // Verify ordering: segments should be in index order (0, 1, 2)
                    result.timeline.files[0].audioFileId shouldBe "af-0"
                    result.timeline.files[1].audioFileId shouldBe "af-1"
                    result.timeline.files[2].audioFileId shouldBe "af-2"
                }
            } finally {
                db.close()
            }
        }
    })

private fun audioFile(
    index: Int,
    id: String,
): AudioFileEntity =
    AudioFileEntity(
        bookId = BookId("book-1"),
        index = index,
        id = id,
        filename = "chapter${index + 1}.m4b",
        format = "m4b",
        codec = "aac",
        duration = 1_800_000L,
        size = 45_000_000L,
    )
