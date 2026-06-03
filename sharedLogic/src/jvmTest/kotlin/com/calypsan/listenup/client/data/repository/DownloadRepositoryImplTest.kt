package com.calypsan.listenup.client.data.repository

import app.cash.turbine.test
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.client.data.remote.PlaybackApiContract
import com.calypsan.listenup.client.data.remote.PreparePlaybackResponse
import com.calypsan.listenup.client.domain.repository.PlaybackPreferences
import com.calypsan.listenup.client.download.DownloadEnqueuer
import com.calypsan.listenup.client.playback.AudioCapabilityDetector
import com.calypsan.listenup.client.data.local.db.AudioFileEntity
import com.calypsan.listenup.client.data.local.db.BookEntity
import com.calypsan.listenup.client.data.local.db.DownloadEntity
import com.calypsan.listenup.client.data.local.db.DownloadState
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.domain.model.BookContributor
import com.calypsan.listenup.client.domain.model.BookDetail
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.DiscoveryBook
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadRepositoryImplTest {
    private val db: ListenUpDatabase = createInMemoryTestDatabase()
    private val downloadDao = db.downloadDao()
    private val bookRepository = FakeBookRepository()
    private val enqueuer = FakeDownloadEnqueuer()
    private val sut =
        DownloadRepositoryImpl(
            downloadDao = downloadDao,
            bookRepository = bookRepository,
            enqueuer = enqueuer,
            playbackApi = FakePlaybackApiContract(),
            playbackPreferences = FakePlaybackPreferences(),
            capabilityDetector = FakeAudioCapabilityDetector(),
        )

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun `empty downloads emits empty list`() =
        runTest {
            sut.observeDownloadedBooks().test {
                assertEquals(emptyList(), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `single completed download becomes one summary row`() =
        runTest {
            bookRepository.books = listOf(fakeBook(id = "b1", title = "Dune", authors = listOf("Frank Herbert")))
            downloadDao.insert(
                makeDownload(id = "f1", bookId = "b1", state = DownloadState.COMPLETED, bytes = 1_000_000L),
            )

            sut.observeDownloadedBooks().test {
                val items = awaitItem()
                assertEquals(1, items.size)
                val summary = items.first()
                assertEquals("b1", summary.bookId)
                assertEquals("Dune", summary.title)
                assertEquals("Frank Herbert", summary.authorNames)
                assertEquals(1_000_000L, summary.sizeBytes)
                assertEquals(1, summary.fileCount)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `multi-file book aggregates size and file count`() =
        runTest {
            bookRepository.books = listOf(fakeBook(id = "b1", title = "Foundation", authors = listOf("Isaac Asimov")))
            downloadDao.insertAll(
                listOf(
                    makeDownload(id = "f1", bookId = "b1", state = DownloadState.COMPLETED, bytes = 500_000L),
                    makeDownload(id = "f2", bookId = "b1", state = DownloadState.COMPLETED, bytes = 300_000L),
                    makeDownload(id = "f3", bookId = "b1", state = DownloadState.COMPLETED, bytes = 200_000L),
                ),
            )

            sut.observeDownloadedBooks().test {
                val items = awaitItem()
                assertEquals(1, items.size)
                val summary = items.first()
                assertEquals(1_000_000L, summary.sizeBytes)
                assertEquals(3, summary.fileCount)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `non-completed downloads are filtered out`() =
        runTest {
            bookRepository.books = listOf(fakeBook(id = "b1", title = "Neverwhere", authors = listOf("Neil Gaiman")))
            downloadDao.insertAll(
                listOf(
                    makeDownload(id = "f1", bookId = "b1", state = DownloadState.QUEUED, bytes = 100_000L),
                    makeDownload(id = "f2", bookId = "b1", state = DownloadState.DOWNLOADING, bytes = 200_000L),
                    makeDownload(id = "f3", bookId = "b1", state = DownloadState.FAILED, bytes = 300_000L),
                    makeDownload(id = "f4", bookId = "b1", state = DownloadState.PAUSED, bytes = 400_000L),
                ),
            )

            sut.observeDownloadedBooks().test {
                assertEquals(emptyList(), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `summaries are sorted by size descending`() =
        runTest {
            bookRepository.books =
                listOf(
                    fakeBook(id = "b1", title = "Small Book", authors = listOf("Author A")),
                    fakeBook(id = "b2", title = "Big Book", authors = listOf("Author B")),
                )
            downloadDao.insertAll(
                listOf(
                    makeDownload(id = "f1", bookId = "b1", state = DownloadState.COMPLETED, bytes = 100_000L),
                    makeDownload(id = "f2", bookId = "b2", state = DownloadState.COMPLETED, bytes = 900_000L),
                ),
            )

            sut.observeDownloadedBooks().test {
                val items = awaitItem()
                assertEquals(2, items.size)
                assertEquals("b2", items[0].bookId)
                assertEquals("b1", items[1].bookId)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `deleteForBook removes all download rows for that book`() =
        runTest {
            bookRepository.books =
                listOf(
                    fakeBook(id = "b1", title = "Deleted", authors = listOf("Author A")),
                    fakeBook(id = "b2", title = "Remaining", authors = listOf("Author B")),
                )
            downloadDao.insertAll(
                listOf(
                    makeDownload(id = "f1", bookId = "b1", state = DownloadState.COMPLETED, bytes = 100_000L),
                    makeDownload(id = "f2", bookId = "b1", state = DownloadState.QUEUED, bytes = 200_000L),
                    makeDownload(id = "f3", bookId = "b2", state = DownloadState.COMPLETED, bytes = 300_000L),
                ),
            )

            sut.deleteForBook("b1")

            // b1 rows are gone; b2 row is untouched
            sut.observeDownloadedBooks().test {
                val items = awaitItem()
                assertEquals(1, items.size)
                assertEquals("b2", items.first().bookId)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `downloads whose book is missing from repository are silently dropped`() =
        runTest {
            bookRepository.books = emptyList()
            downloadDao.insert(
                makeDownload(id = "f1", bookId = "orphan", state = DownloadState.COMPLETED, bytes = 500_000L),
            )

            sut.observeDownloadedBooks().test {
                assertEquals(emptyList(), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ---- Helper factories ----------------------------------------------------------------

    private fun makeDownload(
        id: String,
        bookId: String,
        state: DownloadState,
        bytes: Long,
    ): DownloadEntity =
        DownloadEntity(
            audioFileId = id,
            bookId = bookId,
            filename = "$id.mp3",
            fileIndex = 0,
            state = state,
            localPath = if (state == DownloadState.COMPLETED) "/audio/$id.mp3" else null,
            totalBytes = bytes,
            downloadedBytes = if (state == DownloadState.COMPLETED) bytes else 0L,
            queuedAt = 1_000_000L,
            startedAt = null,
            completedAt = if (state == DownloadState.COMPLETED) 2_000_000L else null,
            errorMessage = null,
            retryCount = 0,
        )

    private fun fakeBook(
        id: String,
        title: String,
        authors: List<String>,
    ): BookListItem =
        BookListItem(
            id = BookId(id),
            libraryId = LibraryId("test-library"),
            folderId = FolderId("test-folder"),
            title = title,
            authors =
                authors.mapIndexed { index, name ->
                    BookContributor(id = "c$index", name = name)
                },
            narrators = emptyList(),
            duration = 0L,
            coverPath = null,
            addedAt = Timestamp(0L),
            updatedAt = Timestamp(0L),
        )
}

private class FakeDownloadEnqueuer : DownloadEnqueuer {
    override suspend fun enqueue(entity: com.calypsan.listenup.client.data.local.db.DownloadEntity): AppResult<Unit> = AppResult.Success(Unit)
}

private class FakePlaybackApiContract : PlaybackApiContract {
    override suspend fun preparePlayback(
        bookId: String,
        audioFileId: String,
        capabilities: List<String>,
        spatial: Boolean,
    ): AppResult<PreparePlaybackResponse> =
        AppResult.Success(
            PreparePlaybackResponse(ready = false, streamUrl = "", variant = "original", codec = "mp3", transcodeJobId = "job1", progress = 0),
        )

    override suspend fun cancelTranscode(jobId: String): AppResult<Unit> = AppResult.Success(Unit)
}

private class FakePlaybackPreferences : PlaybackPreferences {
    override val preferenceChanges: kotlinx.coroutines.flow.SharedFlow<com.calypsan.listenup.client.domain.repository.PreferenceChangeEvent>
        get() = error("not used in test")

    override fun observeDefaultPlaybackSpeed(): kotlinx.coroutines.flow.Flow<Float> = error("not used in test")

    override suspend fun getDefaultPlaybackSpeed(): Float = error("not used in test")

    override suspend fun setDefaultPlaybackSpeed(speed: Float) = error("not used in test")

    override suspend fun getSpatialPlayback(): Boolean = false

    override suspend fun setSpatialPlayback(enabled: Boolean) = error("not used in test")
}

private class FakeAudioCapabilityDetector : AudioCapabilityDetector {
    override fun getSupportedCodecs(): List<String> = listOf("mp3", "aac")
}

private class FakeBookRepository : BookRepository {
    var books: List<BookListItem> = emptyList()

    override suspend fun refreshBooks(): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun getChapters(bookId: String): List<Chapter> = emptyList()

    override fun observeRandomUnstartedBooks(limit: Int): Flow<List<DiscoveryBook>> = flowOf(emptyList())

    override fun observeRecentlyAddedBooks(limit: Int): Flow<List<DiscoveryBook>> = flowOf(emptyList())

    override fun observeBookListItems(): Flow<List<BookListItem>> = flowOf(books)

    override fun observeBookListItems(ids: List<String>): Flow<List<BookListItem>> = flowOf(books.filter { it.id.value in ids })

    override suspend fun getBookListItem(id: String): BookListItem? = books.firstOrNull { it.id.value == id }

    override suspend fun getBookListItems(ids: List<String>): List<BookListItem> = books.filter { it.id.value in ids }

    override fun observeBookDetail(id: String): Flow<BookDetail?> = flowOf(null)

    override fun search(query: String): Flow<List<BookListItem>> = flowOf(emptyList())

    override suspend fun getBookDetail(id: String): BookDetail? = null

    override suspend fun upsertWithAudioFiles(
        book: BookEntity,
        audioFiles: List<AudioFileEntity>,
    ): AppResult<Unit> = AppResult.Success(Unit)
}
