package com.calypsan.listenup.client.data.local.db

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
class SearchDaoTest :
    FunSpec({
        suspend fun seedBook(
            bookDao: BookDao,
            id: String = "b1",
        ) {
            bookDao.upsert(
                BookEntity(
                    id = BookId(id),
                    libraryId = LibraryId("test-library"),
                    folderId = FolderId("test-folder"),
                    title = "Test $id",
                    sortTitle = "Test $id",
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
                    createdAt = Timestamp(1L),
                    updatedAt = Timestamp(1L),
                ),
            )
        }

        suspend fun seedSeries(
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

        suspend fun seedGenre(
            genreDao: GenreDao,
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
                        parentId = null,
                        depth = 0,
                        sortOrder = 0,
                    ),
                ),
            )
        }

        // ========== getGenreNamesForBook ==========

        test("getGenreNamesForBook returns alphabetically-sorted comma-joined names") {
            val db = createInMemoryTestDatabase()
            val bookDao = db.bookDao()
            val genreDao = db.genreDao()
            val searchDao = db.searchDao()
            try {
                runTest {
                    seedBook(bookDao)
                    seedGenre(genreDao, id = "g1", name = "Horror")
                    seedGenre(genreDao, id = "g2", name = "Fantasy")
                    seedGenre(genreDao, id = "g3", name = "Adventure")
                    genreDao.insertAllBookGenres(
                        listOf(
                            BookGenreCrossRef(bookId = BookId("b1"), genreId = "g1"),
                            BookGenreCrossRef(bookId = BookId("b1"), genreId = "g2"),
                            BookGenreCrossRef(bookId = BookId("b1"), genreId = "g3"),
                        ),
                    )

                    val result = searchDao.getGenreNamesForBook("b1")

                    result shouldBe "Adventure, Fantasy, Horror"
                }
            } finally {
                db.close()
            }
        }

        test("getGenreNamesForBook sort is case-insensitive") {
            val db = createInMemoryTestDatabase()
            val bookDao = db.bookDao()
            val genreDao = db.genreDao()
            val searchDao = db.searchDao()
            try {
                runTest {
                    seedBook(bookDao)
                    seedGenre(genreDao, id = "g1", name = "biography")
                    seedGenre(genreDao, id = "g2", name = "Action")
                    genreDao.insertAllBookGenres(
                        listOf(
                            BookGenreCrossRef(bookId = BookId("b1"), genreId = "g1"),
                            BookGenreCrossRef(bookId = BookId("b1"), genreId = "g2"),
                        ),
                    )

                    val result = searchDao.getGenreNamesForBook("b1")

                    // "Action" before "biography" under NOCASE collation despite uppercase A < lowercase b.
                    result shouldBe "Action, biography"
                }
            } finally {
                db.close()
            }
        }

        test("getGenreNamesForBook returns null when book has no genres") {
            val db = createInMemoryTestDatabase()
            val bookDao = db.bookDao()
            val searchDao = db.searchDao()
            try {
                runTest {
                    seedBook(bookDao)

                    searchDao.getGenreNamesForBook("b1") shouldBe null
                }
            } finally {
                db.close()
            }
        }

        // ========== getSeriesNamesForBook ==========

        test("getSeriesNamesForBook returns alphabetically-sorted comma-joined names") {
            val db = createInMemoryTestDatabase()
            val bookDao = db.bookDao()
            val seriesDao = db.seriesDao()
            val bookSeriesDao = db.bookSeriesDao()
            val searchDao = db.searchDao()
            try {
                runTest {
                    seedBook(bookDao)
                    seedSeries(seriesDao, id = "s1", name = "Mistborn Era 1")
                    seedSeries(seriesDao, id = "s2", name = "The Cosmere")
                    seedSeries(seriesDao, id = "s3", name = "Mistborn")
                    bookSeriesDao.insertAll(
                        listOf(
                            BookSeriesCrossRef(bookId = BookId("b1"), seriesId = SeriesId("s1")),
                            BookSeriesCrossRef(bookId = BookId("b1"), seriesId = SeriesId("s2")),
                            BookSeriesCrossRef(bookId = BookId("b1"), seriesId = SeriesId("s3")),
                        ),
                    )

                    val result = searchDao.getSeriesNamesForBook("b1")

                    result shouldBe "Mistborn, Mistborn Era 1, The Cosmere"
                }
            } finally {
                db.close()
            }
        }

        test("getSeriesNamesForBook returns null when book has no series") {
            val db = createInMemoryTestDatabase()
            val bookDao = db.bookDao()
            val searchDao = db.searchDao()
            try {
                runTest {
                    seedBook(bookDao)

                    searchDao.getSeriesNamesForBook("b1") shouldBe null
                }
            } finally {
                db.close()
            }
        }
    })
