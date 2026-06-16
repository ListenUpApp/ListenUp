package com.calypsan.listenup.client.playback

import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.ServerUrl
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.client.data.local.db.BookEntity
import com.calypsan.listenup.client.data.local.db.BookEntityMapper
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.sync.ClientSyncDomainRegistry
import com.calypsan.listenup.client.data.sync.handlers.BookSyncDomainHandler
import com.calypsan.listenup.client.data.remote.SyncApiContract
import com.calypsan.listenup.client.data.remote.model.AudioFileResponse
import com.calypsan.listenup.client.data.remote.model.BookResponse
import com.calypsan.listenup.client.data.repository.BookRepositoryImpl
import com.calypsan.listenup.client.device.DeviceContext
import com.calypsan.listenup.client.device.DeviceType
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.PlaybackPreferences
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.DownloadOutcome
import com.calypsan.listenup.client.download.DownloadService
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.stubImageStorage
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest

/**
 * Verifies fallback-fetch populates the audio_files junction when local data
 * is missing. Seeds a book with NO audio files in the junction; mocks
 * syncApi.getBook to return a BookResponse with audio files; calls
 * prepareForPlayback; asserts the junction is populated after the fallback
 * runs.
 */
class PlaybackManagerFallbackFetchTest :
    FunSpec({
        // Seed the book but do NOT seed audio files.
        suspend fun seedBookWithoutAudioFiles(db: ListenUpDatabase) {
            db.bookDao().upsert(
                BookEntity(
                    id = BookId("book-1"),
                    libraryId = LibraryId("test-library"),
                    folderId = FolderId("test-folder"),
                    title = "Test Book",
                    sortTitle = "Test Book",
                    subtitle = null,
                    coverHash = null,
                    coverBlurHash = null,
                    dominantColor = null,
                    darkMutedColor = null,
                    vibrantColor = null,
                    totalDuration = 3_600_000L,
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
        }

        // Minimal-valid [BookResponse] factory. Only `id` and `audioFiles` matter for
        // this test — everything else is defaulted so the test is insulated from
        // future BookResponse field additions as long as they carry their own
        // defaults. Mirrors the helper in [PlaybackManagerFallbackFetchAtomicityTest].
        fun bookResponseWithAudioFiles(
            id: String,
            audioFiles: List<AudioFileResponse>,
        ): BookResponse =
            BookResponse(
                id = id,
                title = "Fallback Test",
                subtitle = null,
                coverImage = null,
                totalDuration = 3_600_000L,
                description = null,
                genres = null,
                publishYear = null,
                seriesInfo = emptyList(),
                chapters = emptyList(),
                audioFiles = audioFiles,
                contributors = emptyList(),
                createdAt = "2024-01-01T00:00:00Z",
                updatedAt = "2024-01-01T00:00:00Z",
            )

        fun createPlaybackManager(
            db: ListenUpDatabase,
            syncApi: SyncApiContract,
        ): PlaybackManager {
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
            // return null (no saved position), which exercises the fresh-playback path.
            val progressTracker = buildProgressTracker()

            // Real BookRepositoryImpl backed by the same in-memory DB so that
            // fetchBookFromServer's call to upsertWithAudioFiles actually writes
            // to the DB and the junction assertion passes.
            val txRunner = RoomTransactionRunner(db)
            val bookRepository: BookRepository =
                BookRepositoryImpl(
                    bookDao = db.bookDao(),
                    chapterDao = db.chapterDao(),
                    audioFileDao = db.audioFileDao(),
                    searchDao = db.searchDao(),
                    transactionRunner = txRunner,
                    imageStorage = imageStorage,
                    genreRepository = mock(),
                    tagRepository = mock(),
                    moodRepository = mock(),
                    // intentionally unstubbed — this test exercises upsertWithAudioFiles, not the RPC fallback paths
                    networkMonitor = mock(),
                    bookRpcFactory = mock(),
                    bookSyncDomainHandler =
                        BookSyncDomainHandler(
                            database = db,
                            mapper = BookEntityMapper(),
                            transactionRunner = txRunner,
                            imageStorage = stubImageStorage(),
                            registry = ClientSyncDomainRegistry(),
                        ),
                )

            return PlaybackManagerImpl(
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
                playbackRpcFactory = testPlaybackRpcFactory("af-1", "af-2"),
                syncApi = syncApi,
                scope = CoroutineScope(Job()),
                bookRepository = bookRepository,
            )
        }

        test("fallback fetch populates audio_files junction when local is empty") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val syncApi: SyncApiContract = mock()

                    seedBookWithoutAudioFiles(db)

                    everySuspend { syncApi.getBook(any()) } returns
                        AppResult.Success(
                            bookResponseWithAudioFiles(
                                id = "book-1",
                                audioFiles =
                                    listOf(
                                        AudioFileResponse(
                                            id = "af-1",
                                            filename = "chapter01.m4b",
                                            format = "m4b",
                                            codec = "aac",
                                            duration = 1_800_000L,
                                            size = 45_000_000L,
                                        ),
                                        AudioFileResponse(
                                            id = "af-2",
                                            filename = "chapter02.m4b",
                                            format = "m4b",
                                            codec = "aac",
                                            duration = 1_800_000L,
                                            size = 45_000_000L,
                                        ),
                                    ),
                            ),
                        )

                    val playbackManager = createPlaybackManager(db = db, syncApi = syncApi)

                    playbackManager.prepareForPlayback(BookId("book-1"))

                    // After fallback fetch, the junction should be populated.
                    val rows = db.audioFileDao().getForBook("book-1")
                    rows.size shouldBe 2
                    rows.map { it.id } shouldBe listOf("af-1", "af-2")
                    rows.map { it.index } shouldBe listOf(0, 1)
                }
            } finally {
                db.close()
            }
        }
    })
