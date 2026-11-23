package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.domain.model.BookMatchRecord
import app.cash.turbine.test
import com.calypsan.listenup.api.error.DownloadError
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
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.model.TierLabels
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.DiscoveryBook
import com.calypsan.listenup.client.domain.repository.DiscoveryPick
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

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

        test("a summary carries its book's cover, so the Downloads row can draw it") {
            val db = createInMemoryTestDatabase()
            val downloadDao = db.downloadDao()
            try {
                runTest {
                    val bookRepository = FakeBookRepository()
                    val sut =
                        DownloadRepositoryImpl(
                            downloadDao = downloadDao,
                            bookRepository = bookRepository,
                            enqueuer = FakeDownloadEnqueuer(),
                        )

                    bookRepository.books =
                        listOf(
                            fakeBook(id = "b1", title = "The Art of War", authors = listOf("Sun Tzu"))
                                .copy(coverPath = "/covers/b1.jpg", coverHash = "h1"),
                        )
                    downloadDao.insert(
                        makeDownload(id = "f1", bookId = "b1", state = DownloadState.COMPLETED, bytes = 1_000L),
                    )

                    sut.observeDownloadedBooks().test {
                        val summary = awaitItem().single()
                        summary.coverPath shouldBe "/covers/b1.jpg"
                        summary.coverHash shouldBe "h1"
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

        test("a book still downloading is listed with its status, and a finished one is not") {
            val db = createInMemoryTestDatabase()
            val downloadDao = db.downloadDao()
            try {
                runTest {
                    val bookRepository = FakeBookRepository()
                    val sut = DownloadRepositoryImpl(downloadDao, bookRepository, FakeDownloadEnqueuer())
                    bookRepository.books =
                        listOf(
                            fakeBook(id = "b1", title = "Dune", authors = listOf("Frank Herbert")),
                            fakeBook(id = "b2", title = "Circe", authors = listOf("Madeline Miller")),
                        )
                    downloadDao.insertAll(
                        listOf(
                            makeDownload(id = "f1", bookId = "b1", state = DownloadState.QUEUED, bytes = 100L),
                            makeDownload(id = "f2", bookId = "b2", state = DownloadState.COMPLETED, bytes = 100L),
                        ),
                    )

                    sut.observeInProgressBooks().test {
                        val listed = awaitItem().single()
                        listed.bookId shouldBe "b1"
                        listed.title shouldBe "Dune"
                        listed.authorNames shouldBe "Frank Herbert"
                        (listed.status is BookDownloadStatus.InProgress) shouldBe true
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

        // ── B2: cancelled partial must NOT report Completed ──────────────────────────────────

        test("observeBookStatus: partial cancel (3 completed + 7 cancelled) is NOT Completed") {
            val db = createInMemoryTestDatabase()
            val downloadDao = db.downloadDao()
            try {
                runTest {
                    val sut =
                        DownloadRepositoryImpl(
                            downloadDao = downloadDao,
                            bookRepository = FakeBookRepository(),
                            enqueuer = FakeDownloadEnqueuer(),
                        )

                    val rows =
                        (1..10).map { i ->
                            makeDownload(
                                id = "f$i",
                                bookId = "b1",
                                state = if (i <= 3) DownloadState.COMPLETED else DownloadState.CANCELLED,
                                bytes = 100_000L,
                            )
                        }
                    downloadDao.insertAll(rows)

                    sut.observeBookStatus(BookId("b1")).test {
                        val status = awaitItem()
                        // Pre-fix this was BookDownloadStatus.Completed → isFullyDownloaded=true → false
                        // "Downloaded" that fails offline. It must NOT be Completed.
                        (status is BookDownloadStatus.Completed) shouldBe false
                        // It's a stopped partial → Paused (UI offers resume; availability withholds canPlay).
                        (status is BookDownloadStatus.Paused) shouldBe true
                        cancelAndIgnoreRemainingEvents()
                    }
                }
            } finally {
                db.close()
            }
        }

        test("observeBookStatus: all files completed IS Completed") {
            val db = createInMemoryTestDatabase()
            val downloadDao = db.downloadDao()
            try {
                runTest {
                    val sut =
                        DownloadRepositoryImpl(
                            downloadDao = downloadDao,
                            bookRepository = FakeBookRepository(),
                            enqueuer = FakeDownloadEnqueuer(),
                        )
                    downloadDao.insertAll(
                        listOf(
                            makeDownload(id = "f1", bookId = "b1", state = DownloadState.COMPLETED, bytes = 1L),
                            makeDownload(id = "f2", bookId = "b1", state = DownloadState.COMPLETED, bytes = 2L),
                        ),
                    )

                    sut.observeBookStatus(BookId("b1")).test {
                        (awaitItem() is BookDownloadStatus.Completed) shouldBe true
                        cancelAndIgnoreRemainingEvents()
                    }
                }
            } finally {
                db.close()
            }
        }

        test("observeBookStatus: all files cancelled collapses to NotDownloaded") {
            val db = createInMemoryTestDatabase()
            val downloadDao = db.downloadDao()
            try {
                runTest {
                    val sut =
                        DownloadRepositoryImpl(
                            downloadDao = downloadDao,
                            bookRepository = FakeBookRepository(),
                            enqueuer = FakeDownloadEnqueuer(),
                        )
                    downloadDao.insertAll(
                        listOf(
                            makeDownload(id = "f1", bookId = "b1", state = DownloadState.CANCELLED, bytes = 1L),
                            makeDownload(id = "f2", bookId = "b1", state = DownloadState.CANCELLED, bytes = 2L),
                        ),
                    )

                    sut.observeBookStatus(BookId("b1")).test {
                        (awaitItem() is BookDownloadStatus.NotDownloaded) shouldBe true
                        cancelAndIgnoreRemainingEvents()
                    }
                }
            } finally {
                db.close()
            }
        }

        test("observeBookStatus: pure queued reports InProgress (not stomped by the partial-cancel path)") {
            val db = createInMemoryTestDatabase()
            val downloadDao = db.downloadDao()
            try {
                runTest {
                    val sut =
                        DownloadRepositoryImpl(
                            downloadDao = downloadDao,
                            bookRepository = FakeBookRepository(),
                            enqueuer = FakeDownloadEnqueuer(),
                        )
                    downloadDao.insertAll(
                        listOf(
                            makeDownload(id = "f1", bookId = "b1", state = DownloadState.QUEUED, bytes = 1L),
                            makeDownload(id = "f2", bookId = "b1", state = DownloadState.DOWNLOADING, bytes = 2L),
                        ),
                    )

                    sut.observeBookStatus(BookId("b1")).test {
                        (awaitItem() is BookDownloadStatus.InProgress) shouldBe true
                        cancelAndIgnoreRemainingEvents()
                    }
                }
            } finally {
                db.close()
            }
        }

        // ── B1: finish clears only DELETED tombstones ────────────────────────────────────────

        test("deleteDeletedRecordsForBook removes only DELETED rows, preserving COMPLETED downloads") {
            val db = createInMemoryTestDatabase()
            val downloadDao = db.downloadDao()
            try {
                runTest {
                    val sut =
                        DownloadRepositoryImpl(
                            downloadDao = downloadDao,
                            bookRepository = FakeBookRepository(),
                            enqueuer = FakeDownloadEnqueuer(),
                        )
                    downloadDao.insertAll(
                        listOf(
                            makeDownload(id = "f1", bookId = "b1", state = DownloadState.COMPLETED, bytes = 100L),
                            makeDownload(id = "f2", bookId = "b1", state = DownloadState.DELETED, bytes = 200L),
                        ),
                    )

                    sut.deleteDeletedRecordsForBook("b1")

                    // COMPLETED download + local path survive so the finished book still plays offline.
                    sut.getLocalPath("f1") shouldBe "/audio/f1.mp3"
                    sut.getStateForAudioFile("f2") shouldBe null // DELETED tombstone gone
                }
            } finally {
                db.close()
            }
        }

        // The Storage screen walks the whole downloads tree per emission, and progress is written
        // every 256 KB or 500 ms while anything downloads; only a finished file changes the list.
        // A book is downloaded when every file of it is on the device: one finished file of fifty is a book in flight.
        test("a book with a file still in flight is not downloaded; progress does not re-emit, and finishing lists it once") {
            val db = createInMemoryTestDatabase()
            val downloadDao = db.downloadDao()
            try {
                runTest {
                    val bookRepository = FakeBookRepository()
                    val sut =
                        DownloadRepositoryImpl(
                            downloadDao = downloadDao,
                            bookRepository = bookRepository,
                            enqueuer = FakeDownloadEnqueuer(),
                        )
                    bookRepository.books = listOf(fakeBook(id = "b1", title = "Dune", authors = listOf("Frank Herbert")))
                    downloadDao.insert(makeDownload(id = "f1", bookId = "b1", state = DownloadState.COMPLETED, bytes = 1_000L))
                    downloadDao.insert(makeDownload(id = "f2", bookId = "b1", state = DownloadState.DOWNLOADING, bytes = 0L))

                    sut.observeDownloadedBooks().test {
                        awaitItem() shouldBe emptyList()

                        repeat(5) { tick ->
                            downloadDao.updateProgress("f2", downloaded = (tick + 1) * 100L, total = 2_000L)
                            withContext(Dispatchers.Default) { delay(50) }
                        }
                        val _ = downloadDao.markCompleted("f2", localPath = "/audio/f2.mp3", completedAt = 1L)

                        awaitItem().single().fileCount shouldBe 2
                        cancelAndIgnoreRemainingEvents()
                    }
                }
            } finally {
                db.close()
            }
        }

        test("Storage's two lists are disjoint: a part-downloaded book is in flight, never also downloaded") {
            val db = createInMemoryTestDatabase()
            val downloadDao = db.downloadDao()
            try {
                runTest {
                    val bookRepository = FakeBookRepository()
                    val sut = DownloadRepositoryImpl(downloadDao, bookRepository, FakeDownloadEnqueuer())
                    bookRepository.books =
                        listOf(
                            fakeBook(id = "b1", title = "Dune", authors = listOf("Frank Herbert")),
                            fakeBook(id = "b2", title = "Circe", authors = listOf("Madeline Miller")),
                            fakeBook(id = "b3", title = "Emma", authors = listOf("Jane Austen")),
                        )
                    downloadDao.insertAll(
                        listOf(
                            makeDownload(id = "a1", bookId = "b1", state = DownloadState.COMPLETED, bytes = 100L),
                            makeDownload(id = "a2", bookId = "b1", state = DownloadState.QUEUED, bytes = 100L),
                            makeDownload(id = "c1", bookId = "b2", state = DownloadState.COMPLETED, bytes = 100L),
                            makeDownload(id = "c2", bookId = "b2", state = DownloadState.PAUSED, bytes = 100L),
                            makeDownload(id = "e1", bookId = "b3", state = DownloadState.COMPLETED, bytes = 100L),
                            makeDownload(id = "e2", bookId = "b3", state = DownloadState.COMPLETED, bytes = 100L),
                        ),
                    )

                    sut.observeDownloadedBooks().test {
                        awaitItem().map { it.bookId } shouldBe listOf("b3")
                        cancelAndIgnoreRemainingEvents()
                    }
                    sut.observeInProgressBooks().test {
                        awaitItem().map { it.bookId }.toSet() shouldBe setOf("b1", "b2")
                        cancelAndIgnoreRemainingEvents()
                    }
                }
            } finally {
                db.close()
            }
        }

        // The book side is observed too: a title or cover that syncs in later shows without waiting
        // for the downloads table to change.
        test("a downloaded book's new title re-emits") {
            val db = createInMemoryTestDatabase()
            val downloadDao = db.downloadDao()
            try {
                runTest {
                    val bookRepository = FakeBookRepository()
                    val sut =
                        DownloadRepositoryImpl(
                            downloadDao = downloadDao,
                            bookRepository = bookRepository,
                            enqueuer = FakeDownloadEnqueuer(),
                        )
                    bookRepository.books = listOf(fakeBook(id = "b1", title = "Dune", authors = listOf("Frank Herbert")))
                    downloadDao.insert(makeDownload(id = "f1", bookId = "b1", state = DownloadState.COMPLETED, bytes = 1_000L))

                    sut.observeDownloadedBooks().test {
                        awaitItem().single().title shouldBe "Dune"

                        bookRepository.books =
                            listOf(fakeBook(id = "b1", title = "Dune Messiah", authors = listOf("Frank Herbert")))

                        awaitItem().single().title shouldBe "Dune Messiah"
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

        test("markFailed reports how many failures the row has now spent") {
            val db = createInMemoryTestDatabase()
            val downloadDao = db.downloadDao()
            try {
                runTest {
                    downloadDao.insert(
                        DownloadEntity(
                            audioFileId = "af1",
                            bookId = "b1",
                            filename = "af1.m4b",
                            fileIndex = 0,
                            state = DownloadState.DOWNLOADING,
                            localPath = null,
                            totalBytes = 1000L,
                            downloadedBytes = 0L,
                            queuedAt = 0L,
                            startedAt = null,
                            completedAt = null,
                            errorMessage = null,
                            retryCount = 1,
                        ),
                    )
                    val sut =
                        DownloadRepositoryImpl(
                            downloadDao = downloadDao,
                            bookRepository = FakeBookRepository(),
                            enqueuer = FakeDownloadEnqueuer(),
                        )

                    sut.markFailed("af1", DownloadError.DownloadFailed()) shouldBe AppResult.Success(2)
                    downloadDao.getByAudioFileId("af1")?.state shouldBe DownloadState.FAILED
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
    override suspend fun enqueue(entity: com.calypsan.listenup.client.data.local.db.DownloadEntity): AppResult<Unit> =
        AppResult.Success(Unit)
}

private class FakeBookRepository : BookRepository {
    /** Observed like Room: a write to [books] re-emits every [observeBookListItems] collector. */
    private val booksFlow = MutableStateFlow<List<BookListItem>>(emptyList())

    var books: List<BookListItem>
        get() = booksFlow.value
        set(value) {
            booksFlow.value = value
        }

    override suspend fun refreshBooks(): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun getChapters(bookId: String): List<Chapter> = emptyList()

    override fun observeChapters(bookId: String): Flow<List<Chapter>> = flowOf(emptyList())

    override fun observeBookTierLabels(bookId: String): Flow<TierLabels> = flowOf(TierLabels.None)

    override fun observeMatchRecord(bookId: String): Flow<BookMatchRecord?> = flowOf(null)

    override fun observeIsBookLive(id: String): Flow<Boolean> = flowOf(true)

    override fun observeRandomUnstartedBooks(limit: Int): Flow<List<DiscoveryPick>> = flowOf(emptyList())

    override fun observeRecentlyAddedBooks(limit: Int): Flow<List<DiscoveryBook>> = flowOf(emptyList())

    override fun observeBookListItems(): Flow<List<BookListItem>> = flowOf(books)

    override fun observeBookListItems(ids: List<String>): Flow<List<BookListItem>> =
        booksFlow.map { all -> all.filter { it.id.value in ids } }

    override suspend fun getBookListItem(id: String): BookListItem? = books.firstOrNull { it.id.value == id }

    override suspend fun getBookListItems(ids: List<String>): List<BookListItem> = books.filter { it.id.value in ids }

    override fun observeBookDetail(id: String): Flow<BookDetail?> = flowOf(null)

    override fun observeBookExists(id: String): Flow<Boolean> = flowOf(false)

    override fun observeBookDurationMs(id: String): Flow<Long?> = flowOf(null)

    override fun observeFirstSeriesId(id: String): Flow<String?> = flowOf(null)

    override fun search(query: String): Flow<List<BookListItem>> = flowOf(emptyList())

    override suspend fun getBookDetail(id: String): BookDetail? = null

    /** Not exercised here — these fakes cover read paths, and a delete is a server-only write. */
    override suspend fun deleteBook(id: BookId): AppResult<Unit> = AppResult.Success(Unit)
}
