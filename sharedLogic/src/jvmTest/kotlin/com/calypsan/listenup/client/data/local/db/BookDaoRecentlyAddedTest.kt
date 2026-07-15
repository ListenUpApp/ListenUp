package com.calypsan.listenup.client.data.local.db

import app.cash.turbine.test
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/**
 * Regression coverage for the Discover "Recently Added" half-list bug.
 *
 * Prior to this fix [BookDao.observeRecentlyAddedWithAuthor] silently applied
 * a `bs.sequence IN ('1', '0', '0.5')` filter, hiding every mid-series book from
 * Discover. The DAO method name did not advertise this filter — the repository layer
 * simply called it and handed the truncated list to the UI.
 *
 * The fix (per the rubric rule "Query-shaping lives in the repository, not the DAO;
 * DAO methods are named for exactly what they return"):
 *  - [BookDao.observeRecentlyAddedWithAuthor] is now a neutral query (no series filter).
 *
 * These tests seed a standalone book, a first-in-series book and a mid-series book,
 * then assert that the neutral method returns all three.
 */
class BookDaoRecentlyAddedTest :
    FunSpec({

        test("observeRecentlyAddedWithAuthor returns all recently added books regardless of series sequence") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val bookDao = db.bookDao()
                    val seriesDao = db.seriesDao()
                    val bookSeriesDao = db.bookSeriesDao()

                    // Three books across the full sequence spectrum. createdAt timestamps are
                    // distinct so the ORDER BY is deterministic — newest first.
                    recentlyAddedSeedBook(bookDao, id = "standalone", createdAt = 3_000L)
                    recentlyAddedSeedBook(bookDao, id = "first-in-series", createdAt = 2_000L)
                    recentlyAddedSeedBook(bookDao, id = "mid-series", createdAt = 1_000L)

                    recentlyAddedSeedSeries(seriesDao, id = "s1", name = "Test Series")
                    recentlyAddedLinkBookToSeries(bookSeriesDao, bookId = "first-in-series", seriesId = "s1", sequence = "1")
                    recentlyAddedLinkBookToSeries(bookSeriesDao, bookId = "mid-series", seriesId = "s1", sequence = "3")

                    bookDao.observeRecentlyAddedWithAuthor(limit = 10).test {
                        val emitted = awaitItem().map { it.id.value }
                        emitted shouldBe listOf("standalone", "first-in-series", "mid-series")
                        cancelAndIgnoreRemainingEvents()
                    }
                }
            } finally {
                db.close()
            }
        }
    })

private suspend fun recentlyAddedSeedBook(
    bookDao: BookDao,
    id: String,
    createdAt: Long,
) {
    bookDao.upsert(
        BookEntity(
            id = BookId(id),
            libraryId = LibraryId("test-library"),
            folderId = FolderId("test-folder"),
            title = "Book $id",
            sortTitle = "Book $id",
            subtitle = null,
            coverHash = null,
            totalDuration = 0L,
            description = null,
            publishYear = null,
            publisher = null,
            language = null,
            isbn = null,
            asin = null,
            abridged = false,
            createdAt = Timestamp(createdAt),
            updatedAt = Timestamp(createdAt),
        ),
    )
}

private suspend fun recentlyAddedSeedSeries(
    seriesDao: SeriesDao,
    id: String,
    name: String,
) {
    seriesDao.upsert(
        SeriesEntity(
            id = SeriesId(id),
            name = name,
            description = null,
            createdAt = Timestamp(1L),
            updatedAt = Timestamp(1L),
        ),
    )
}

private suspend fun recentlyAddedLinkBookToSeries(
    bookSeriesDao: BookSeriesDao,
    bookId: String,
    seriesId: String,
    sequence: String?,
) {
    bookSeriesDao.insertAll(
        listOf(
            BookSeriesCrossRef(
                bookId = BookId(bookId),
                seriesId = SeriesId(seriesId),
                sequence = sequence,
            ),
        ),
    )
}
