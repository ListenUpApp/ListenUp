package com.calypsan.listenup.client.data.local.db

import app.cash.turbine.test
import com.calypsan.listenup.client.core.BookId
import com.calypsan.listenup.client.core.SeriesId
import com.calypsan.listenup.client.core.Timestamp
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression coverage for the Discover "Recently Added" half-list bug (W6 Phase A, Bug 5).
 *
 * Prior to this test suite [BookDao.observeRecentlyAddedWithAuthor] silently applied
 * a `bs.sequence IN ('1', '0', '0.5')` filter, hiding every mid-series book from
 * Discover. The DAO method name did not advertise this filter — the repository layer
 * simply called it and handed the truncated list to the UI.
 *
 * The fix (per the rubric rule "Query-shaping lives in the repository, not the DAO;
 * DAO methods are named for exactly what they return"):
 *  - [BookDao.observeRecentlyAddedWithAuthor] is now a neutral query (no series filter).
 *  - [BookDao.observeRecentlyAddedFirstInSeriesWithAuthor] carries the original filter
 *    under a name that tells the truth.
 *
 * These tests seed a standalone book, a first-in-series book and a mid-series book,
 * then assert each method emits the set its name claims to emit.
 */
class BookDaoRecentlyAddedTest {
    private val db: ListenUpDatabase = createInMemoryTestDatabase()
    private val bookDao = db.bookDao()
    private val seriesDao = db.seriesDao()
    private val bookSeriesDao = db.bookSeriesDao()

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private suspend fun seedBook(
        id: String,
        createdAt: Long,
    ) {
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
                createdAt = Timestamp(createdAt),
                updatedAt = Timestamp(createdAt),
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

    @Test
    fun `observeRecentlyAddedWithAuthor returns all recently added books regardless of series sequence`() =
        runTest {
            // Three books across the full sequence spectrum. createdAt timestamps are
            // distinct so the ORDER BY is deterministic — newest first.
            seedBook(id = "standalone", createdAt = 3_000L)
            seedBook(id = "first-in-series", createdAt = 2_000L)
            seedBook(id = "mid-series", createdAt = 1_000L)

            seedSeries(id = "s1", name = "Test Series")
            linkBookToSeries(bookId = "first-in-series", seriesId = "s1", sequence = "1")
            linkBookToSeries(bookId = "mid-series", seriesId = "s1", sequence = "3")

            bookDao.observeRecentlyAddedWithAuthor(limit = 10).test {
                val emitted = awaitItem().map { it.id.value }
                assertEquals(
                    listOf("standalone", "first-in-series", "mid-series"),
                    emitted,
                    "neutral query must include mid-series books; DAO name must not lie",
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `observeRecentlyAddedFirstInSeriesWithAuthor excludes mid-series books`() =
        runTest {
            seedBook(id = "standalone", createdAt = 3_000L)
            seedBook(id = "first-in-series", createdAt = 2_000L)
            seedBook(id = "mid-series", createdAt = 1_000L)

            seedSeries(id = "s1", name = "Test Series")
            linkBookToSeries(bookId = "first-in-series", seriesId = "s1", sequence = "1")
            linkBookToSeries(bookId = "mid-series", seriesId = "s1", sequence = "3")

            bookDao.observeRecentlyAddedFirstInSeriesWithAuthor(limit = 10).test {
                val emitted = awaitItem().map { it.id.value }
                assertEquals(
                    listOf("standalone", "first-in-series"),
                    emitted,
                    "filtered query must drop mid-series books (sequence NOT IN '1','0','0.5')",
                )
                cancelAndIgnoreRemainingEvents()
            }
        }
}
