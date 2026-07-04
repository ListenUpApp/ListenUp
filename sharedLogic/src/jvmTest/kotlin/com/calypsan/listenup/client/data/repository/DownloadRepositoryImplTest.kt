package com.calypsan.listenup.client.data.repository

import app.cash.turbine.test
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.client.download.DownloadEnqueuer
import com.calypsan.listenup.client.data.local.db.DownloadEntity
import com.calypsan.listenup.client.data.local.db.DownloadState
import com.calypsan.listenup.client.domain.model.BookContributor
import com.calypsan.listenup.client.domain.model.BookDetail
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.DiscoveryBook
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

class DownloadRepositoryImplTest :
    FunSpec({
        test("empty downloads emits empty list") {
            val db = createInMemoryTestDatabase()
            val downloadDao = db.downloadDao()
            try {
                runTest {
                    val bookRepository = FakeBookRepository()
                    val enqueuer = FakeDownloadEnqueuer()
                    val sut =
                        DownloadRepositoryImpl(
                            downloadDao = downloadDao,
                            bookRepository = bookRepository,
                            enqueuer = enqueuer,
                        )

                    sut.observeDownloadedBooks().test {
                        awaitItem() shouldBe emptyList()
                        cancelAndIgnoreRemainingEvents()
                    }
                }
            } finally {
                db.close()
            }
        }

        test("single completed download becomes one summary row") {
            val db = createInMemoryTestDatabase()
            val downloadDao = db.downloadDao()
            try {
                runTest {
                    val bookRepository = FakeBookRepository()
                    val enqueuer = FakeDownloadEnqueuer()
                    val sut =
                        DownloadRepositoryImpl(
                            downloadDao = downloadDao,
                            bookRepository = bookRepository,
                            enqueuer = enqueuer,
                        )

                    bookRepository.books =
                        listOf(fakeBook(id = "b1", title = "Dune", authors = listOf("Frank Herbert")))
                    downloadDao.insert(
                        makeDownload(id = "f1", bookId = "b1", state = DownloadState.COMPLETED, bytes = 1_000_000L),
                    )

                    sut.observeDownloadedBooks().test {
                        val items = awaitItem()
                        items.size shouldBe 1
                        val summary = items.first()
                        summary.bookId shouldBe "b1"
                        summary.title shouldBe "Dune"
                        summary.authorNames shouldBe "Frank Herbert"
                        summary.sizeBytes shouldBe 1_000_000L
                        summary.fileCount shouldBe 1
                        cancelAndIgnoreRemainingEvents()
                    }
                }
            } finally {
                db.close()
            }
        }

        test("multi-file book aggregates size and file count") {
            val db = createInMemoryTestDatabase()
            val downloadDao = db.downloadDao()
            try {
                runTest {
                    val bookRepository = FakeBookRepository()
                    val enqueuer = FakeDownloadEnqueuer()
                    val sut =
                        DownloadRepositoryImpl(
                            downloadDao = downloadDao,
                            bookRepository = bookRepository,
                            enqueuer = enqueuer,
                        )

                    bookRepository.books =
                        listOf(fakeBook(id = "b1", title = "Foundation", authors = listOf("Isaac Asimov")))
                    downloadDao.insertAll(
                        listOf(
                            makeDownload(id = "f1", bookId = "b1", state = DownloadState.COMPLETED, bytes = 500_000L),
                            makeDownload(id = "f2", bookId = "b1", state = DownloadState.COMPLETED, bytes = 300_000L),
                            makeDownload(id = "f3", bookId = "b1", state = DownloadState.COMPLETED, bytes = 200_000L),
                        ),
                    )

                    sut.observeDownloadedBooks().test {
                        val items = awaitItem()
                        items.size shouldBe 1
                        val summary = items.first()
                        summary.sizeBytes shouldBe 1_000_000L
                        summary.fileCount shouldBe 3
                        cancelAndIgnoreRemainingEvents()
                    }
                }
            } finally {
                db.close()
            }
        }

        test("non-completed downloads are filtered out") {
            val db = createInMemoryTestDatabase()
            val downloadDao = db.downloadDao()
            try {
                runTest {
                    val bookRepository = FakeBookRepository()
                    val enqueuer = FakeDownloadEnqueuer()
                    val sut =
                        DownloadRepositoryImpl(
                            downloadDao = downloadDao,
                            bookRepository = bookRepository,
                            enqueuer = enqueuer,
                        )

                    bookRepository.books =
                        listOf(fakeBook(id = "b1", title = "Neverwhere", authors = listOf("Neil Gaiman")))
                    downloadDao.insertAll(
                        listOf(
                            makeDownload(id = "f1", bookId = "b1", state = DownloadState.QUEUED, bytes = 100_000L),
                            makeDownload(id = "f2", bookId = "b1", state = DownloadState.DOWNLOADING, bytes = 200_000L),
                            makeDownload(id = "f3", bookId = "b1", state = DownloadState.FAILED, bytes = 300_000L),
                            makeDownload(id = "f4", bookId = "b1", state = DownloadState.PAUSED, bytes = 400_000L),
                        ),
                    )

                    sut.observeDownloadedBooks().test {
                        awaitItem() shouldBe emptyList()
                        cancelAndIgnoreRemainingEvents()
                    }
                }
            } finally {
                db.close()
            }
        }

        test("summaries are sorted by size descending") {
            val db = createInMemoryTestDatabase()
            val downloadDao = db.downloadDao()
            try {
                runTest {
                    val bookRepository = FakeBookRepository()
                    val enqueuer = FakeDownloadEnqueuer()
                    val sut =
                        DownloadRepositoryImpl(
                            downloadDao = downloadDao,
                            bookRepository = bookRepository,
                            enqueuer = enqueuer,
                        )

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
                        items.size shouldBe 2
                        items[0].bookId shouldBe "b2"
                        items[1].bookId shouldBe "b1"
                        cancelAndIgnoreRemainingEvents()
                    }
                }
            } finally {
                db.close()
            }
        }

        test("deleteForBook removes all download rows for that book") {
            val db = createInMemoryTestDatabase()
            val downloadDao = db.downloadDao()
            try {
                runTest {
                    val bookRepository = FakeBookRepository()
                    val enqueuer = FakeDownloadEnqueuer()
                    val sut =
                        DownloadRepositoryImpl(
                            downloadDao = downloadDao,
                            bookRepository = bookRepository,
                            enqueuer = enqueuer,
                        )

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
                        items.size shouldBe 1
                        items.first().bookId shouldBe "b2"
                        cancelAndIgnoreRemainingEvents()
                    }
                }
            } finally {
                db.close()
            }
        }

        test("downloads whose book is missing from repository are silently dropped") {
            val db = createInMemoryTestDatabase()
            val downloadDao = db.downloadDao()
            try {
                runTest {
                    val bookRepository = FakeBookRepository()
                    val enqueuer = FakeDownloadEnqueuer()
                    val sut =
                        DownloadRepositoryImpl(
                            downloadDao = downloadDao,
                            bookRepository = bookRepository,
                            enqueuer = enqueuer,
                        )

                    bookRepository.books = emptyList()
                    downloadDao.insert(
                        makeDownload(id = "f1", bookId = "orphan", state = DownloadState.COMPLETED, bytes = 500_000L),
                    )

                    sut.observeDownloadedBooks().test {
                        awaitItem() shouldBe emptyList()
                        cancelAndIgnoreRemainingEvents()
                    }
                }
            } finally {
                db.close()
            }
        }
    })

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

private class FakeDownloadEnqueuer : DownloadEnqueuer {
    override suspend fun enqueue(entity: com.calypsan.listenup.client.data.local.db.DownloadEntity): AppResult<Unit> = AppResult.Success(Unit)
}

private class FakeBookRepository : BookRepository {
    var books: List<BookListItem> = emptyList()

    override suspend fun refreshBooks(): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun getChapters(bookId: String): List<Chapter> = emptyList()

    override fun observeChapters(bookId: String): Flow<List<Chapter>> = flowOf(emptyList())

    override fun observeIsBookLive(id: String): Flow<Boolean> = flowOf(true)

    override fun observeRandomUnstartedBooks(limit: Int): Flow<List<DiscoveryBook>> = flowOf(emptyList())

    override fun observeRecentlyAddedBooks(limit: Int): Flow<List<DiscoveryBook>> = flowOf(emptyList())

    override fun observeBookListItems(): Flow<List<BookListItem>> = flowOf(books)

    override fun observeBookListItems(ids: List<String>): Flow<List<BookListItem>> = flowOf(books.filter { it.id.value in ids })

    override suspend fun getBookListItem(id: String): BookListItem? = books.firstOrNull { it.id.value == id }

    override suspend fun getBookListItems(ids: List<String>): List<BookListItem> = books.filter { it.id.value in ids }

    override fun observeBookDetail(id: String): Flow<BookDetail?> = flowOf(null)

    override fun search(query: String): Flow<List<BookListItem>> = flowOf(emptyList())

    override suspend fun getBookDetail(id: String): BookDetail? = null
}
