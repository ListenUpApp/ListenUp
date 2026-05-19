package com.calypsan.listenup.client.data.local.db

import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Verifies [SearchDao]'s two GROUP_CONCAT queries against a real in-memory
 * [ListenUpDatabase]. Both `getSeriesNamesForBook` and `getGenreNamesForBook`
 * have the same shape — join a parent table through a junction, aggregate names
 * into a comma-joined string for FTS indexing.
 *
 * Previously these queries were only exercised indirectly through `FtsPopulatorTest`
 * with Mokkery stubs, which proved "FtsPopulator passes through whatever the DAO
 * returns" but not that the SQL itself delivers the right string for real junction
 * rows. FU-A1 closes that gap and also verifies the `ORDER BY name COLLATE NOCASE`
 * added to make FTS content deterministic across sync runs.
 */
class SearchDaoTest {
    private val db: ListenUpDatabase = createInMemoryTestDatabase()
    private val bookDao = db.bookDao()
    private val seriesDao = db.seriesDao()
    private val genreDao = db.genreDao()
    private val bookSeriesDao = db.bookSeriesDao()
    private val searchDao = db.searchDao()

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private suspend fun seedBook(id: String = "b1") {
        bookDao.upsert(
            BookEntity(
                id = BookId(id),
                title = "Test $id",
                sortTitle = "Test $id",
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

    private suspend fun seedGenre(
        id: String,
        name: String,
    ) {
        genreDao.upsertAll(
            listOf(
                GenreEntity(
                    id = id,
                    name = name,
                    slug = id,
                    path = "/$id",
                    bookCount = 0,
                    parentId = null,
                    depth = 0,
                    sortOrder = 0,
                ),
            ),
        )
    }

    // ========== getGenreNamesForBook ==========

    @Test
    fun `getGenreNamesForBook returns alphabetically-sorted comma-joined names`() =
        runTest {
            seedBook()
            seedGenre(id = "g1", name = "Horror")
            seedGenre(id = "g2", name = "Fantasy")
            seedGenre(id = "g3", name = "Adventure")
            genreDao.insertAllBookGenres(
                listOf(
                    BookGenreCrossRef(bookId = BookId("b1"), genreId = "g1"),
                    BookGenreCrossRef(bookId = BookId("b1"), genreId = "g2"),
                    BookGenreCrossRef(bookId = BookId("b1"), genreId = "g3"),
                ),
            )

            val result = searchDao.getGenreNamesForBook("b1")

            assertEquals("Adventure, Fantasy, Horror", result)
        }

    @Test
    fun `getGenreNamesForBook sort is case-insensitive`() =
        runTest {
            seedBook()
            seedGenre(id = "g1", name = "biography")
            seedGenre(id = "g2", name = "Action")
            genreDao.insertAllBookGenres(
                listOf(
                    BookGenreCrossRef(bookId = BookId("b1"), genreId = "g1"),
                    BookGenreCrossRef(bookId = BookId("b1"), genreId = "g2"),
                ),
            )

            val result = searchDao.getGenreNamesForBook("b1")

            // "Action" before "biography" under NOCASE collation despite uppercase A < lowercase b.
            assertEquals("Action, biography", result)
        }

    @Test
    fun `getGenreNamesForBook returns null when book has no genres`() =
        runTest {
            seedBook()

            assertNull(searchDao.getGenreNamesForBook("b1"))
        }

    // ========== getSeriesNamesForBook ==========

    @Test
    fun `getSeriesNamesForBook returns alphabetically-sorted comma-joined names`() =
        runTest {
            seedBook()
            seedSeries(id = "s1", name = "Mistborn Era 1")
            seedSeries(id = "s2", name = "The Cosmere")
            seedSeries(id = "s3", name = "Mistborn")
            bookSeriesDao.insertAll(
                listOf(
                    BookSeriesCrossRef(bookId = BookId("b1"), seriesId = SeriesId("s1")),
                    BookSeriesCrossRef(bookId = BookId("b1"), seriesId = SeriesId("s2")),
                    BookSeriesCrossRef(bookId = BookId("b1"), seriesId = SeriesId("s3")),
                ),
            )

            val result = searchDao.getSeriesNamesForBook("b1")

            assertEquals("Mistborn, Mistborn Era 1, The Cosmere", result)
        }

    @Test
    fun `getSeriesNamesForBook returns null when book has no series`() =
        runTest {
            seedBook()

            assertNull(searchDao.getSeriesNamesForBook("b1"))
        }
}
