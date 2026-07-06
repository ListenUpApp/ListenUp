package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookSyncPayload
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
import com.calypsan.listenup.client.data.sync.domains.booksDomain
import com.calypsan.listenup.client.data.sync.domains.toHandler
import com.calypsan.listenup.api.BookService
import com.calypsan.listenup.client.data.remote.BookRpcFactory
import com.calypsan.listenup.client.device.DeviceContext
import com.calypsan.listenup.client.device.DeviceType
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.PlaybackPreferences
import com.calypsan.listenup.client.domain.repository.ServerConfig
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
 * Verifies the playback REST fallback persists a complete book aggregate when local data is
 * missing. Seeds a book with NO audio files in the junction; stubs `bookService.getBook` to return the
 * contract [BookSyncPayload] the Kotlin server actually emits; calls `prepareForPlayback`; asserts
 * the junction is populated — INCLUDING the audio-stream fields (`codecProfile`/`spatial`/`bitrate`/
 * `sampleRate`/`channels`) that were dropped on the stale `SingleBookResponse` path.
 *
 * Uses a real in-memory DB + a real books sync handler so it exercises the full decode →
 * persist path the fallback now shares with the RPC on-demand fetch.
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

        // Minimal-valid [BookSyncPayload] (the wire type GET /api/v1/books/{id} returns). Only `id`
        // and `audioFiles` matter here; everything else is a sensible constant.
        fun bookPayloadWithAudioFiles(
            id: String,
            audioFiles: List<BookAudioFilePayload>,
        ): BookSyncPayload =
            BookSyncPayload(
                id = id,
                libraryId = LibraryId("test-library"),
                folderId = FolderId("test-folder"),
                title = "Fallback Test",
                sortTitle = null,
                subtitle = null,
                description = null,
                publishYear = null,
                publisher = null,
                language = null,
                isbn = null,
                asin = null,
                abridged = false,
                explicit = false,
                totalDuration = 3_600_000L,
                cover = null,
                rootRelPath = "books/fallback-test",
                inode = null,
                scannedAt = 1L,
                contributors = emptyList(),
                series = emptyList(),
                audioFiles = audioFiles,
                chapters = emptyList(),
                revision = 1L,
                updatedAt = 1L,
                createdAt = 1L,
                deletedAt = null,
            )

        fun createPlaybackManager(
            db: ListenUpDatabase,
            bookRpcFactory: BookRpcFactory,
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

            // Real books sync handler backed by the same in-memory DB so the fallback's
            // onCatchUpItem actually writes the aggregate and the junction assertion passes.
            val bookSyncDomainHandler =
                booksDomain(
                    database = db,
                    mapper = BookEntityMapper(),
                    imageStorage = stubImageStorage(),
                ).toHandler(transactionRunner = RoomTransactionRunner(db), registry = ClientSyncDomainRegistry())

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
                playbackRpcFactory = testPlaybackRpcFactory("af-1", "af-2"),
                bookRpcFactory = bookRpcFactory,
                scope = CoroutineScope(Job()),
                bookSyncDomainHandler = bookSyncDomainHandler,
            )
        }

        test("fallback fetch populates audio_files junction (incl. audio-stream fields) when local is empty") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val bookService: BookService = mock()
                    val bookRpcFactory: BookRpcFactory = mock()
                    everySuspend { bookRpcFactory.bookService() } returns bookService

                    seedBookWithoutAudioFiles(db)

                    everySuspend { bookService.getBook(any()) } returns
                        AppResult.Success(
                            bookPayloadWithAudioFiles(
                                id = "book-1",
                                audioFiles =
                                    listOf(
                                        BookAudioFilePayload(
                                            id = "af-1",
                                            index = 0,
                                            filename = "chapter01.m4b",
                                            format = "m4b",
                                            codec = "aac",
                                            duration = 1_800_000L,
                                            size = 45_000_000L,
                                            codecProfile = "lc",
                                            spatial = "atmos",
                                            bitrate = 128_000,
                                            sampleRate = 44_100,
                                            channels = 2,
                                        ),
                                        BookAudioFilePayload(
                                            id = "af-2",
                                            index = 1,
                                            filename = "chapter02.m4b",
                                            format = "m4b",
                                            codec = "aac",
                                            duration = 1_800_000L,
                                            size = 45_000_000L,
                                        ),
                                    ),
                            ),
                        )

                    val playbackManager = createPlaybackManager(db = db, bookRpcFactory = bookRpcFactory)

                    playbackManager.prepareForPlayback(BookId("book-1"))

                    // After fallback fetch, the junction should be populated...
                    val rows = db.audioFileDao().getForBook("book-1")
                    rows.size shouldBe 2
                    rows.map { it.id } shouldBe listOf("af-1", "af-2")
                    rows.map { it.index } shouldBe listOf(0, 1)

                    // ...and the audio-stream fields must survive the fallback write.
                    val first = rows.first { it.id == "af-1" }
                    first.codecProfile shouldBe "lc"
                    first.spatial shouldBe "atmos"
                    first.bitrate shouldBe 128_000
                    first.sampleRate shouldBe 44_100
                    first.channels shouldBe 2

                    // A file without stream metadata stays null — no spurious defaults.
                    val second = rows.first { it.id == "af-2" }
                    second.codecProfile shouldBe null
                    second.bitrate shouldBe null
                }
            } finally {
                db.close()
            }
        }
    })
