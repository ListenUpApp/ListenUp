package com.calypsan.listenup.client.data.sync.pull

import com.calypsan.listenup.client.core.BookId
import com.calypsan.listenup.client.core.Timestamp
import com.calypsan.listenup.client.data.local.db.AudioFileDao
import com.calypsan.listenup.client.data.local.db.AudioFileEntity
import com.calypsan.listenup.client.data.local.db.BookContributorCrossRef
import com.calypsan.listenup.client.data.local.db.BookContributorDao
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.BookEntity
import com.calypsan.listenup.client.data.local.db.BookGenreCrossRef
import com.calypsan.listenup.client.data.local.db.BookSeriesCrossRef
import com.calypsan.listenup.client.data.local.db.BookSeriesDao
import com.calypsan.listenup.client.data.local.db.BookTagCrossRef
import com.calypsan.listenup.client.data.local.db.ChapterDao
import com.calypsan.listenup.client.data.local.db.ChapterEntity
import com.calypsan.listenup.client.data.local.db.GenreDao
import com.calypsan.listenup.client.data.local.db.GenreIdName
import com.calypsan.listenup.client.data.local.db.TagDao
import com.calypsan.listenup.client.data.local.db.TagEntity
import com.calypsan.listenup.client.data.remote.SyncApiContract
import com.calypsan.listenup.client.data.remote.model.AudioFileResponse
import com.calypsan.listenup.client.data.remote.model.BookContributorResponse
import com.calypsan.listenup.client.data.remote.model.BookResponse
import com.calypsan.listenup.client.data.remote.model.BookSeriesInfoResponse
import com.calypsan.listenup.client.data.remote.model.ChapterResponse
import com.calypsan.listenup.client.data.remote.model.SyncBooksResponse
import com.calypsan.listenup.client.data.sync.ImageDownloaderContract
import com.calypsan.listenup.client.data.sync.conflict.BookConflict
import com.calypsan.listenup.client.data.sync.conflict.ConflictDetectorContract
import com.calypsan.listenup.client.data.sync.model.SyncPhase
import com.calypsan.listenup.client.data.sync.model.SyncStatus
import dev.mokkery.answering.returns
import dev.mokkery.answering.sequentially
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.matcher.matches
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode
import dev.mokkery.verifySuspend
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.client.core.Failure

/**
 * Tests for BookPuller.
 *
 * Tests cover:
 * - Pagination handling
 * - Progress reporting
 * - Deletion handling
 * - Conflict detection integration
 * - Book upsert
 * - Chapter syncing
 * - Relationship syncing (contributors, series)
 * - Cover downloading
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BookPullerTest {
    private fun createBookResponse(
        id: String = "book-1",
        title: String = "Test Book",
        contributors: List<BookContributorResponse> = emptyList(),
        seriesInfo: List<BookSeriesInfoResponse> = emptyList(),
        chapters: List<ChapterResponse> = emptyList(),
        genres: List<String>? = null,
        audioFiles: List<AudioFileResponse> = emptyList(),
    ): BookResponse =
        BookResponse(
            id = id,
            title = title,
            subtitle = null,
            coverImage = null,
            totalDuration = 3_600_000L,
            description = null,
            genres = genres,
            publishYear = null,
            seriesInfo = seriesInfo,
            chapters = chapters,
            audioFiles = audioFiles,
            contributors = contributors,
            createdAt = "2024-01-01T00:00:00Z",
            updatedAt = "2024-01-01T00:00:00Z",
        )

    private fun createChapterResponse(
        title: String = "Chapter 1",
        startTime: Long = 0L,
        endTime: Long = 60000L,
    ): ChapterResponse =
        ChapterResponse(
            title = title,
            startTime = startTime,
            endTime = endTime,
        )

    private fun createContributorResponse(
        id: String = "contrib-1",
        name: String = "Test Author",
        roles: List<String> = listOf("author"),
    ): BookContributorResponse =
        BookContributorResponse(
            contributorId = id,
            name = name,
            roles = roles,
            creditedAs = null,
        )

    private fun createSeriesInfoResponse(
        seriesId: String = "series-1",
        name: String = "Test Series",
        sequence: String? = "1",
    ): BookSeriesInfoResponse =
        BookSeriesInfoResponse(
            seriesId = seriesId,
            name = name,
            sequence = sequence,
        )

    private class TestFixture(
        val scope: TestScope,
    ) {
        val transactionRunner: com.calypsan.listenup.client.data.local.db.TransactionRunner =
            object : com.calypsan.listenup.client.data.local.db.TransactionRunner {
                override suspend fun <R> atomically(block: suspend () -> R): R = block()
            }
        val syncApi: SyncApiContract = mock()
        val bookDao: BookDao = mock()
        val chapterDao: ChapterDao = mock()
        val bookContributorDao: BookContributorDao = mock()
        val bookSeriesDao: BookSeriesDao = mock()
        val tagDao: TagDao = mock()
        val genreDao: GenreDao = mock()
        val audioFileDao: AudioFileDao = mock()
        val conflictDetector: ConflictDetectorContract = mock()
        val imageDownloader: ImageDownloaderContract = mock()
        val coverDownloadDao: com.calypsan.listenup.client.data.local.db.CoverDownloadDao = mock()

        init {
            // Default stubs
            everySuspend { bookDao.upsertAll(any<List<BookEntity>>()) } returns Unit
            everySuspend { bookDao.deleteByIds(any()) } returns Unit
            everySuspend { bookDao.markConflict(any(), any()) } returns Unit
            everySuspend { bookDao.getById(any()) } returns null // No existing book by default
            everySuspend { chapterDao.upsertAll(any<List<ChapterEntity>>()) } returns Unit
            everySuspend { bookContributorDao.deleteContributorsForBook(any()) } returns Unit
            everySuspend { bookContributorDao.insertAll(any<List<BookContributorCrossRef>>()) } returns Unit
            everySuspend { bookSeriesDao.deleteSeriesForBook(any()) } returns Unit
            everySuspend { bookSeriesDao.insertAll(any<List<BookSeriesCrossRef>>()) } returns Unit
            everySuspend { tagDao.upsertAll(any<List<TagEntity>>()) } returns Unit
            everySuspend { tagDao.deleteTagsForBook(any()) } returns Unit
            everySuspend { tagDao.insertAllBookTags(any<List<BookTagCrossRef>>()) } returns Unit
            everySuspend { genreDao.deleteGenresForBook(any()) } returns Unit
            everySuspend { genreDao.getIdsByNames(any()) } returns emptyList()
            everySuspend { genreDao.insertAllBookGenres(any<List<BookGenreCrossRef>>()) } returns Unit
            everySuspend { audioFileDao.deleteForBook(any()) } returns Unit
            everySuspend { audioFileDao.upsertAll(any<List<AudioFileEntity>>()) } returns Unit
            everySuspend { conflictDetector.detectBookConflicts(any()) } returns emptyList()
            everySuspend { conflictDetector.shouldPreserveLocalChanges(any()) } returns false
            everySuspend { imageDownloader.deleteCover(any()) } returns Success(Unit)
            everySuspend { coverDownloadDao.enqueueAll(any()) } returns Unit
        }

        fun build(): BookPuller {
            val bookRelationshipWriter =
                BookRelationshipWriter(
                    bookContributorDao = bookContributorDao,
                    bookSeriesDao = bookSeriesDao,
                    tagDao = tagDao,
                    genreDao = genreDao,
                    audioFileDao = audioFileDao,
                )
            return BookPuller(
                transactionRunner = transactionRunner,
                syncApi = syncApi,
                bookDao = bookDao,
                chapterDao = chapterDao,
                genreDao = genreDao,
                bookRelationshipWriter = bookRelationshipWriter,
                conflictDetector = conflictDetector,
                imageDownloader = imageDownloader,
                coverDownloadDao = coverDownloadDao,
            )
        }
    }

    // ========== Basic Pull Tests ==========

    @Test
    fun `pull with empty response completes successfully`() =
        runTest {
            // Given
            val fixture = TestFixture(this)
            everySuspend { fixture.syncApi.getBooks(any(), any(), any()) } returns
                Success(
                    SyncBooksResponse(
                        books = emptyList(),
                        deletedBookIds = emptyList(),
                        nextCursor = null,
                        hasMore = false,
                    ),
                )
            val puller = fixture.build()

            // When
            puller.pull(null) {}

            // Then - no upserts for empty response
            verifySuspend(VerifyMode.not) { fixture.bookDao.upsertAll(any<List<BookEntity>>()) }
        }

    @Test
    fun `pull upserts books from server`() =
        runTest {
            // Given
            val fixture = TestFixture(this)
            everySuspend { fixture.syncApi.getBooks(any(), any(), any()) } returns
                Success(
                    SyncBooksResponse(
                        books = listOf(createBookResponse(id = "book-1", title = "Test Book")),
                        deletedBookIds = emptyList(),
                        nextCursor = null,
                        hasMore = false,
                    ),
                )
            val puller = fixture.build()

            // When
            puller.pull(null) {}
            advanceUntilIdle()

            // Then
            verifySuspend { fixture.bookDao.upsertAll(any<List<BookEntity>>()) }
        }

    @Test
    fun `pull returns Failure on API failure`() =
        runTest {
            // Given
            val fixture = TestFixture(this)
            everySuspend { fixture.syncApi.getBooks(any(), any(), any()) } returns
                Failure(RuntimeException("API error"))
            val puller = fixture.build()

            // When
            val result = puller.pull(null) {}

            // Then
            assertIs<AppResult.Failure>(result)
        }

    // ========== Pagination Tests ==========

    @Test
    fun `pull handles pagination correctly`() =
        runTest {
            // Given - two pages of results
            val fixture = TestFixture(this)
            everySuspend { fixture.syncApi.getBooks(any(), any(), any()) } sequentially {
                returns(
                    Success(
                        SyncBooksResponse(
                            books = listOf(createBookResponse(id = "book-1")),
                            deletedBookIds = emptyList(),
                            nextCursor = "cursor-1",
                            hasMore = true,
                        ),
                    ),
                )
                returns(
                    Success(
                        SyncBooksResponse(
                            books = listOf(createBookResponse(id = "book-2")),
                            deletedBookIds = emptyList(),
                            nextCursor = null,
                            hasMore = false,
                        ),
                    ),
                )
            }
            val puller = fixture.build()
            val progressUpdates = mutableListOf<SyncStatus>()

            // When
            puller.pull(null) { progressUpdates.add(it) }
            advanceUntilIdle()

            // Then - should have progress updates for both pages
            val bookSyncProgress =
                progressUpdates
                    .filterIsInstance<SyncStatus.Progress>()
                    .filter { it.phase == SyncPhase.SYNCING_BOOKS }
            assertEquals(2, bookSyncProgress.size)
        }

    // ========== Deletion Tests ==========

    @Test
    fun `pull handles book deletions`() =
        runTest {
            // Given
            val fixture = TestFixture(this)
            everySuspend { fixture.syncApi.getBooks(any(), any(), any()) } returns
                Success(
                    SyncBooksResponse(
                        books = emptyList(),
                        deletedBookIds = listOf("deleted-1", "deleted-2"),
                        nextCursor = null,
                        hasMore = false,
                    ),
                )
            val puller = fixture.build()

            // When
            puller.pull(null) {}

            // Then
            verifySuspend { fixture.bookDao.deleteByIds(any()) }
        }

    // ========== Conflict Detection Tests ==========

    @Test
    fun `pull detects and marks conflicts`() =
        runTest {
            // Given
            val fixture = TestFixture(this)
            val serverTimestamp = Timestamp(5000L)
            everySuspend { fixture.syncApi.getBooks(any(), any(), any()) } returns
                Success(
                    SyncBooksResponse(
                        books = listOf(createBookResponse(id = "book-1")),
                        deletedBookIds = emptyList(),
                        nextCursor = null,
                        hasMore = false,
                    ),
                )
            everySuspend { fixture.conflictDetector.detectBookConflicts(any()) } returns
                listOf(BookConflict(BookId("book-1"), serverTimestamp))
            val puller = fixture.build()

            // When
            puller.pull(null) {}
            advanceUntilIdle()

            // Then
            verifySuspend { fixture.bookDao.markConflict(BookId("book-1"), serverTimestamp) }
        }

    @Test
    fun `pull skips books with newer local changes`() =
        runTest {
            // Given
            val fixture = TestFixture(this)
            everySuspend { fixture.syncApi.getBooks(any(), any(), any()) } returns
                Success(
                    SyncBooksResponse(
                        books = listOf(createBookResponse(id = "book-1")),
                        deletedBookIds = emptyList(),
                        nextCursor = null,
                        hasMore = false,
                    ),
                )
            // All books should be preserved (local is newer)
            everySuspend { fixture.conflictDetector.shouldPreserveLocalChanges(any()) } returns true
            val puller = fixture.build()

            // When
            puller.pull(null) {}
            advanceUntilIdle()

            // Then - no upsert because local changes are newer
            verifySuspend(VerifyMode.not) { fixture.bookDao.upsertAll(any<List<BookEntity>>()) }
        }

    // ========== Chapter Sync Tests ==========

    @Test
    fun `pull syncs chapters for books`() =
        runTest {
            // Given
            val fixture = TestFixture(this)
            everySuspend { fixture.syncApi.getBooks(any(), any(), any()) } returns
                Success(
                    SyncBooksResponse(
                        books =
                            listOf(
                                createBookResponse(
                                    id = "book-1",
                                    chapters =
                                        listOf(
                                            createChapterResponse(title = "Chapter 1"),
                                            createChapterResponse(title = "Chapter 2"),
                                        ),
                                ),
                            ),
                        deletedBookIds = emptyList(),
                        nextCursor = null,
                        hasMore = false,
                    ),
                )
            val puller = fixture.build()

            // When
            puller.pull(null) {}
            advanceUntilIdle()

            // Then
            verifySuspend { fixture.chapterDao.upsertAll(any<List<ChapterEntity>>()) }
        }

    // ========== Contributor Relationship Tests ==========

    @Test
    fun `pull syncs book contributors`() =
        runTest {
            // Given
            val fixture = TestFixture(this)
            everySuspend { fixture.syncApi.getBooks(any(), any(), any()) } returns
                Success(
                    SyncBooksResponse(
                        books =
                            listOf(
                                createBookResponse(
                                    id = "book-1",
                                    contributors =
                                        listOf(
                                            createContributorResponse(id = "author-1", roles = listOf("author")),
                                        ),
                                ),
                            ),
                        deletedBookIds = emptyList(),
                        nextCursor = null,
                        hasMore = false,
                    ),
                )
            val puller = fixture.build()

            // When
            puller.pull(null) {}
            advanceUntilIdle()

            // Then - delete old, insert new
            verifySuspend { fixture.bookContributorDao.deleteContributorsForBook(BookId("book-1")) }
            verifySuspend { fixture.bookContributorDao.insertAll(any<List<BookContributorCrossRef>>()) }
        }

    // ========== Series Relationship Tests ==========

    @Test
    fun `pull syncs book series relationships`() =
        runTest {
            // Given
            val fixture = TestFixture(this)
            everySuspend { fixture.syncApi.getBooks(any(), any(), any()) } returns
                Success(
                    SyncBooksResponse(
                        books =
                            listOf(
                                createBookResponse(
                                    id = "book-1",
                                    seriesInfo =
                                        listOf(
                                            createSeriesInfoResponse(seriesId = "series-1"),
                                        ),
                                ),
                            ),
                        deletedBookIds = emptyList(),
                        nextCursor = null,
                        hasMore = false,
                    ),
                )
            val puller = fixture.build()

            // When
            puller.pull(null) {}
            advanceUntilIdle()

            // Then - delete old, insert new
            verifySuspend { fixture.bookSeriesDao.deleteSeriesForBook(BookId("book-1")) }
            verifySuspend { fixture.bookSeriesDao.insertAll(any<List<BookSeriesCrossRef>>()) }
        }

    // ========== Cover Download Tests ==========

    @Test
    fun `pull triggers cover downloads in background`() =
        runTest {
            // Given
            val fixture = TestFixture(this)
            everySuspend { fixture.syncApi.getBooks(any(), any(), any()) } returns
                Success(
                    SyncBooksResponse(
                        books = listOf(createBookResponse(id = "book-1")),
                        deletedBookIds = emptyList(),
                        nextCursor = null,
                        hasMore = false,
                    ),
                )
            val puller = fixture.build()

            // When
            puller.pull(null) {}
            advanceUntilIdle()

            // Then
            verifySuspend { fixture.coverDownloadDao.enqueueAll(any()) }
        }

    // ========== Progress Reporting Tests ==========

    @Test
    fun `pull reports progress for each page`() =
        runTest {
            // Given
            val fixture = TestFixture(this)
            everySuspend { fixture.syncApi.getBooks(any(), any(), any()) } returns
                Success(
                    SyncBooksResponse(
                        books = listOf(createBookResponse(id = "book-1")),
                        deletedBookIds = emptyList(),
                        nextCursor = null,
                        hasMore = false,
                    ),
                )
            val puller = fixture.build()
            val progressUpdates = mutableListOf<SyncStatus>()

            // When
            puller.pull(null) { progressUpdates.add(it) }
            advanceUntilIdle()

            // Then
            assertTrue(progressUpdates.isNotEmpty())
            val bookProgress = progressUpdates.first { it is SyncStatus.Progress }
            assertEquals(SyncPhase.SYNCING_BOOKS, (bookProgress as SyncStatus.Progress).phase)
        }

    // ========== Delta Sync Tests ==========

    @Test
    fun `pull passes updatedAfter for delta sync`() =
        runTest {
            // Given
            val fixture = TestFixture(this)
            val timestamp = "2024-01-01T00:00:00Z"
            everySuspend { fixture.syncApi.getBooks(any(), any(), timestamp) } returns
                Success(
                    SyncBooksResponse(
                        books = emptyList(),
                        deletedBookIds = emptyList(),
                        nextCursor = null,
                        hasMore = false,
                    ),
                )
            val puller = fixture.build()

            // When
            puller.pull(timestamp) {}

            // Then - API called with timestamp
            verifySuspend { fixture.syncApi.getBooks(any(), any(), timestamp) }
        }

    @Test
    fun `pull passes null for full sync`() =
        runTest {
            // Given
            val fixture = TestFixture(this)
            everySuspend { fixture.syncApi.getBooks(any(), any(), null) } returns
                Success(
                    SyncBooksResponse(
                        books = emptyList(),
                        deletedBookIds = emptyList(),
                        nextCursor = null,
                        hasMore = false,
                    ),
                )
            val puller = fixture.build()

            // When
            puller.pull(null) {}

            // Then - API called with null (full sync)
            verifySuspend { fixture.syncApi.getBooks(any(), any(), null) }
        }

    // ========== Genre Sync Tests ==========

    @Test
    fun `syncBookGenres resolves names to ids and writes junction rows`() =
        runTest {
            // Given — server sends a book with two genre names that both resolve.
            val fixture = TestFixture(this)
            everySuspend { fixture.syncApi.getBooks(any(), any(), any()) } returns
                Success(
                    SyncBooksResponse(
                        books =
                            listOf(
                                createBookResponse(
                                    id = "book-1",
                                    genres = listOf("Fantasy", "Horror"),
                                ),
                            ),
                        deletedBookIds = emptyList(),
                        nextCursor = null,
                        hasMore = false,
                    ),
                )
            everySuspend { fixture.genreDao.getIdsByNames(any()) } returns
                listOf(
                    GenreIdName(id = "g1", name = "Fantasy"),
                    GenreIdName(id = "g3", name = "Horror"),
                )
            val puller = fixture.build()

            // When
            puller.pull(null) {}
            advanceUntilIdle()

            // Then — delete previous junctions for the book, then insert both cross-refs.
            verifySuspend { fixture.genreDao.deleteGenresForBook(BookId("book-1")) }
            verifySuspend {
                fixture.genreDao.insertAllBookGenres(
                    listOf(
                        BookGenreCrossRef(bookId = BookId("book-1"), genreId = "g1"),
                        BookGenreCrossRef(bookId = BookId("book-1"), genreId = "g3"),
                    ),
                )
            }
        }

    @Test
    fun `syncBookGenres drops unresolved names with a warning`() =
        runTest {
            // Given — server sends two names but only "Fantasy" is in the local catalog.
            val fixture = TestFixture(this)
            everySuspend { fixture.syncApi.getBooks(any(), any(), any()) } returns
                Success(
                    SyncBooksResponse(
                        books =
                            listOf(
                                createBookResponse(
                                    id = "book-1",
                                    genres = listOf("Fantasy", "UnknownGenre"),
                                ),
                            ),
                        deletedBookIds = emptyList(),
                        nextCursor = null,
                        hasMore = false,
                    ),
                )
            everySuspend { fixture.genreDao.getIdsByNames(any()) } returns
                listOf(GenreIdName(id = "g1", name = "Fantasy"))
            val puller = fixture.build()

            // When
            puller.pull(null) {}
            advanceUntilIdle()

            // Then — only the resolved cross-ref is inserted; unknown name is dropped.
            verifySuspend {
                fixture.genreDao.insertAllBookGenres(
                    listOf(BookGenreCrossRef(bookId = BookId("book-1"), genreId = "g1")),
                )
            }
        }

    // ========== Audio File Sync Tests ==========

    @Test
    fun `syncBookAudioFiles writes junction rows for each audio file`() =
        runTest {
            // Given — server sends a book with two audio files.
            val fixture = TestFixture(this)
            val audioFiles =
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
                )
            everySuspend { fixture.syncApi.getBooks(any(), any(), any()) } returns
                Success(
                    SyncBooksResponse(
                        books =
                            listOf(
                                createBookResponse(
                                    id = "book-1",
                                    audioFiles = audioFiles,
                                ),
                            ),
                        deletedBookIds = emptyList(),
                        nextCursor = null,
                        hasMore = false,
                    ),
                )
            val puller = fixture.build()

            // When
            puller.pull(null) {}
            advanceUntilIdle()

            // Then — indexed rows for each audio file are upserted into the junction.
            verifySuspend {
                fixture.audioFileDao.upsertAll(
                    matches { rows: List<AudioFileEntity> ->
                        rows.size == 2 &&
                            rows.all { it.bookId == BookId("book-1") } &&
                            rows.map { it.index } == listOf(0, 1) &&
                            rows.map { it.id } == listOf("af-1", "af-2")
                    },
                )
            }
        }

    @Test
    fun `syncBookAudioFiles deletes existing rows before inserting`() =
        runTest {
            // Given — server sends a book with one audio file.
            val fixture = TestFixture(this)
            everySuspend { fixture.syncApi.getBooks(any(), any(), any()) } returns
                Success(
                    SyncBooksResponse(
                        books =
                            listOf(
                                createBookResponse(
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
                                        ),
                                ),
                            ),
                        deletedBookIds = emptyList(),
                        nextCursor = null,
                        hasMore = false,
                    ),
                )
            val puller = fixture.build()

            // When
            puller.pull(null) {}
            advanceUntilIdle()

            // Then — server-authoritative: delete existing rows first, then insert.
            verifySuspend { fixture.audioFileDao.deleteForBook("book-1") }
            verifySuspend { fixture.audioFileDao.upsertAll(any<List<AudioFileEntity>>()) }
        }
}
