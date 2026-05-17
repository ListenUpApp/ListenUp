package com.calypsan.listenup.client.data.local.db

import app.cash.turbine.test
import com.calypsan.listenup.client.core.BookId
import com.calypsan.listenup.client.core.SeriesId
import com.calypsan.listenup.client.core.Timestamp
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression coverage for the Discover "Random Unstarted" silent-filter bug (W6 Phase F, Drift #1).
 *
 * Prior to this test suite, three DAO methods silently applied a
 * `bs.sequence IN ('1', '0', '0.5')` filter, hiding every mid-series book from
 * Discover. The method names did not advertise this filter.
 *
 * The fix (per the rubric rule "Query-shaping lives in the repository, not the DAO;
 * DAO methods are named for exactly what they return"):
 *  - [BookDao.observeRandomUnstartedBooks] and [BookDao.observeRandomUnstartedBooksWithAuthor]
 *    are neutral queries (no series filter).
 *
 * These tests seed a mid-series book (sequence "3") alongside a standalone and a
 * first-in-series book, then assert that neutral methods include the mid-series book.
 */
class BookDaoRandomUnstartedTest {
    private val db: ListenUpDatabase = createInMemoryTestDatabase()
    private val bookDao = db.bookDao()
    private val seriesDao = db.seriesDao()
    private val bookSeriesDao = db.bookSeriesDao()

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private suspend fun seedBook(id: String) {
        bookDao.upsert(
            BookEntity(
                id = BookId(id),
                title = "Book $id",
                sortTitle = "Book $id",
                subtitle = null,
                coverHash = null,
                coverBlurHash = null,
                dominantColor = null,
                darkMutedColor = null,
                vibrantColor = null,
                totalDuration = 0L,
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

    private suspend fun seedSeries(
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

    private suspend fun linkBookToSeries(
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

    /** Seeds: standalone, first-in-series (sequence "1"), mid-series (sequence "3"). */
    private suspend fun seedThreeBooks() {
        seedBook(id = "standalone")
        seedBook(id = "first-in-series")
        seedBook(id = "mid-series")

        seedSeries(id = "s1", name = "Test Series")
        linkBookToSeries(bookId = "first-in-series", seriesId = "s1", sequence = "1")
        linkBookToSeries(bookId = "mid-series", seriesId = "s1", sequence = "3")
    }

    // ── observeRandomUnstartedBooks ──────────────────────────────────────────────

    @Test
    fun `observeRandomUnstartedBooks returns mid-series books (neutral, no sequence filter)`() =
        runTest {
            seedThreeBooks()

            bookDao.observeRandomUnstartedBooks(limit = 10).test {
                val emitted = awaitItem().map { it.id.value }.toSet()
                assertTrue(
                    "mid-series" in emitted,
                    "neutral query must include mid-series book; DAO name must not lie",
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ── observeRandomUnstartedBooksWithAuthor ────────────────────────────────────

    @Test
    fun `observeRandomUnstartedBooksWithAuthor returns mid-series books (neutral, no sequence filter)`() =
        runTest {
            seedThreeBooks()

            bookDao.observeRandomUnstartedBooksWithAuthor(limit = 10).test {
                val emitted = awaitItem().map { it.id.value }.toSet()
                assertTrue(
                    "mid-series" in emitted,
                    "neutral query must include mid-series book; DAO name must not lie",
                )
                cancelAndIgnoreRemainingEvents()
            }
        }
}
