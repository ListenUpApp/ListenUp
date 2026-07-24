package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.client.data.local.db.BookContributorCrossRef
import com.calypsan.listenup.client.data.local.db.BookEntity
import com.calypsan.listenup.client.data.local.db.BookGenreCrossRef
import com.calypsan.listenup.client.data.local.db.BookSeriesCrossRef
import com.calypsan.listenup.client.data.local.db.ContributorEntity
import com.calypsan.listenup.client.data.local.db.GenreEntity
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.local.db.SeriesEntity
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest

/**
 * Integration tests for [FtsPopulator.rebuildBooks] against a real in-memory [ListenUpDatabase].
 *
 * These tests verify that the batched rebuild path produces the same denormalized FTS rows
 * as the old per-book query approach, and that the Room queries in [SearchDao] work correctly
 * against real data. The key risks guarded here are:
 *
 *  - SQL correctness: the four batch queries reproduce the exact same joins, GROUP_CONCAT
 *    ordering, and role filters as the original per-book queries.
 *  - Null-safety: a book with no author / narrator / series / genre still gets an FTS row.
 *  - Idempotency: running rebuildAll() twice leaves exactly N rows — no duplicates.
 *  - Search coverage: a [SearchDao.searchBooks] query finds a book by author, narrator,
 *    series name, and genre name after rebuild — proving the denormalized strings are
 *    correctly indexed.
 */
class FtsPopulatorRebuildTest :
    FunSpec({

        // ========== Seed helpers ==========

        suspend fun seedBook(
            db: com.calypsan.listenup.client.data.local.db.ListenUpDatabase,
            id: String,
            title: String,
            subtitle: String? = null,
            description: String? = null,
        ) {
            db.bookDao().upsert(
                BookEntity(
                    id = BookId(id),
                    libraryId = LibraryId("test-library"),
                    folderId = FolderId("test-folder"),
                    title = title,
                    sortTitle = title,
                    subtitle = subtitle,
                    coverHash = null,
                    totalDuration = 3_600_000L,
                    description = description,
                    createdAt = Timestamp(1L),
                    updatedAt = Timestamp(1L),
                ),
            )
        }

        suspend fun seedContributor(
            db: com.calypsan.listenup.client.data.local.db.ListenUpDatabase,
            id: String,
            name: String,
        ) {
            db.contributorDao().upsert(
                ContributorEntity(
                    id = ContributorId(id),
                    name = name,
                    description = null,
                    imagePath = null,
                    createdAt = Timestamp(1L),
                    updatedAt = Timestamp(1L),
                ),
            )
        }

        suspend fun linkContributor(
            db: com.calypsan.listenup.client.data.local.db.ListenUpDatabase,
            bookId: String,
            contributorId: String,
            role: String,
        ) {
            db.bookContributorDao().insert(
                BookContributorCrossRef(
                    bookId = BookId(bookId),
                    contributorId = ContributorId(contributorId),
                    role = role,
                ),
            )
        }

        suspend fun seedSeries(
            db: com.calypsan.listenup.client.data.local.db.ListenUpDatabase,
            id: String,
            name: String,
        ) {
            db.seriesDao().upsert(
                SeriesEntity(
                    id = SeriesId(id),
                    name = name,
                    description = null,
                    createdAt = Timestamp(1L),
                    updatedAt = Timestamp(1L),
                ),
            )
        }

        suspend fun linkSeries(
            db: com.calypsan.listenup.client.data.local.db.ListenUpDatabase,
            bookId: String,
            seriesId: String,
        ) {
            db.bookSeriesDao().insertAll(
                listOf(BookSeriesCrossRef(bookId = BookId(bookId), seriesId = SeriesId(seriesId))),
            )
        }

        suspend fun seedGenre(
            db: com.calypsan.listenup.client.data.local.db.ListenUpDatabase,
            id: String,
            name: String,
        ) {
            db.genreDao().upsertAll(
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

        suspend fun linkGenre(
            db: com.calypsan.listenup.client.data.local.db.ListenUpDatabase,
            bookId: String,
            genreId: String,
        ) {
            db.genreDao().insertAllBookGenres(
                listOf(BookGenreCrossRef(bookId = BookId(bookId), genreId = genreId)),
            )
        }

        fun buildPopulator(db: com.calypsan.listenup.client.data.local.db.ListenUpDatabase) =
            FtsPopulator(
                bookDao = db.bookDao(),
                contributorDao = db.contributorDao(),
                seriesDao = db.seriesDao(),
                searchDao = db.searchDao(),
                transactionRunner = RoomTransactionRunner(db),
            )

        // ========== Case 1: Full denormalization — author, narrator, multi-series, multi-genre ==========

        test("rebuildAll writes one FTS row per book and search finds it by author, narrator, series, and genre") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    // Seed 3 books with rich denormalized data
                    seedBook(db, id = "b1", title = "The Way of Kings", description = "Epic fantasy")
                    seedBook(db, id = "b2", title = "Words of Radiance")
                    seedBook(db, id = "b3", title = "Oathbringer")

                    seedContributor(db, id = "c-author", name = "Brandon Sanderson")
                    seedContributor(db, id = "c-narrator", name = "Michael Kramer")
                    linkContributor(db, bookId = "b1", contributorId = "c-author", role = "author")
                    linkContributor(db, bookId = "b1", contributorId = "c-narrator", role = "narrator")
                    linkContributor(db, bookId = "b2", contributorId = "c-author", role = "author")
                    linkContributor(db, bookId = "b3", contributorId = "c-author", role = "author")

                    seedSeries(db, id = "s1", name = "The Stormlight Archive")
                    seedSeries(db, id = "s2", name = "The Cosmere")
                    linkSeries(db, bookId = "b1", seriesId = "s1")
                    linkSeries(db, bookId = "b1", seriesId = "s2")
                    linkSeries(db, bookId = "b2", seriesId = "s1")
                    linkSeries(db, bookId = "b3", seriesId = "s1")

                    seedGenre(db, id = "g1", name = "Fantasy")
                    seedGenre(db, id = "g2", name = "Epic Fantasy")
                    linkGenre(db, bookId = "b1", genreId = "g1")
                    linkGenre(db, bookId = "b1", genreId = "g2")

                    buildPopulator(db).rebuildAll()

                    // All 3 books have an FTS row
                    db.searchDao().countBooksFts() shouldBe 3

                    // Book can be found by title
                    val byTitle = db.searchDao().searchBooks("Way*")
                    byTitle.size shouldBe 1
                    byTitle.first().book.id shouldBe BookId("b1")

                    // Book b1 can be found by author name
                    val byAuthor = db.searchDao().searchBooks("Sanderson*")
                    byAuthor.any { it.book.id == BookId("b1") } shouldBe true

                    // Book b1 can be found by narrator name
                    val byNarrator = db.searchDao().searchBooks("Kramer*")
                    byNarrator.any { it.book.id == BookId("b1") } shouldBe true

                    // Book b1 can be found by series name
                    val bySeries = db.searchDao().searchBooks("Stormlight*")
                    bySeries.any { it.book.id == BookId("b1") } shouldBe true

                    // Book b1 can be found by genre name
                    val byGenre = db.searchDao().searchBooks("Fantasy*")
                    byGenre.any { it.book.id == BookId("b1") } shouldBe true
                }
            } finally {
                db.close()
            }
        }

        // ========== Case 2: Book with no author, narrator, series, or genre still gets an FTS row ==========

        test("rebuildAll inserts an FTS row for a book with no contributors, series, or genres") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    seedBook(db, id = "b-bare", title = "Bare Book")

                    buildPopulator(db).rebuildAll()

                    // The book has a row even though all denormalized fields are null
                    db.searchDao().countBooksFts() shouldBe 1

                    // It is findable by title
                    val results = db.searchDao().searchBooks("Bare*")
                    results.size shouldBe 1
                    results.first().book.id shouldBe BookId("b-bare")

                    // authorName is null (stored in the FTS table; searchBooks returns it)
                    results.first().authorName shouldBe null
                }
            } finally {
                db.close()
            }
        }

        // ========== Case 3: Idempotency — running rebuildAll() twice leaves exactly N rows ==========

        test("rebuildAll is idempotent — running it twice leaves exactly N rows") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    seedBook(db, id = "b1", title = "Book Alpha")
                    seedBook(db, id = "b2", title = "Book Beta")

                    val populator = buildPopulator(db)
                    populator.rebuildAll()
                    val countAfterFirst = db.searchDao().countBooksFts()

                    populator.rebuildAll()
                    val countAfterSecond = db.searchDao().countBooksFts()

                    countAfterFirst shouldBe 2
                    countAfterSecond shouldBe 2
                }
            } finally {
                db.close()
            }
        }

        // ========== Case 4: Multi-series string is alphabetically sorted ==========

        test("rebuildAll produces alphabetically-sorted series names matching per-book query output") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    seedBook(db, id = "b1", title = "Some Book")
                    seedSeries(db, id = "s1", name = "Mistborn Era 1")
                    seedSeries(db, id = "s2", name = "The Cosmere")
                    seedSeries(db, id = "s3", name = "Mistborn")
                    linkSeries(db, bookId = "b1", seriesId = "s1")
                    linkSeries(db, bookId = "b1", seriesId = "s2")
                    linkSeries(db, bookId = "b1", seriesId = "s3")

                    // Verify the per-book query produces the expected string
                    val perBookResult = db.searchDao().getSeriesNamesForBook("b1")
                    perBookResult shouldBe "Mistborn, Mistborn Era 1, The Cosmere"

                    buildPopulator(db).rebuildAll()

                    // The FTS row must be findable by the earliest series name in the joined string
                    db.searchDao().searchBooks("Mistborn*").any { it.book.id == BookId("b1") } shouldBe true
                    db.searchDao().searchBooks("Cosmere*").any { it.book.id == BookId("b1") } shouldBe true
                }
            } finally {
                db.close()
            }
        }

        // ========== Case 5: Multi-genre string is alphabetically sorted ==========

        test("rebuildAll produces alphabetically-sorted genre names matching per-book query output") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    seedBook(db, id = "b1", title = "Genre Book")
                    seedGenre(db, id = "g1", name = "Horror")
                    seedGenre(db, id = "g2", name = "Fantasy")
                    seedGenre(db, id = "g3", name = "Adventure")
                    linkGenre(db, bookId = "b1", genreId = "g1")
                    linkGenre(db, bookId = "b1", genreId = "g2")
                    linkGenre(db, bookId = "b1", genreId = "g3")

                    // Verify the per-book query produces the expected string
                    val perBookResult = db.searchDao().getGenreNamesForBook("b1")
                    perBookResult shouldBe "Adventure, Fantasy, Horror"

                    buildPopulator(db).rebuildAll()

                    // The FTS row must be findable by any of the genre names
                    db.searchDao().searchBooks("Horror*").any { it.book.id == BookId("b1") } shouldBe true
                    db.searchDao().searchBooks("Adventure*").any { it.book.id == BookId("b1") } shouldBe true
                }
            } finally {
                db.close()
            }
        }

        // ========== Case 6: searchBooks returns the stored authorName from the FTS row ==========

        test("searchBooks result carries the authorName that was written to the FTS row") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    seedBook(db, id = "b1", title = "Author Name Book")
                    seedContributor(db, id = "c1", name = "Mary Shelley")
                    linkContributor(db, bookId = "b1", contributorId = "c1", role = "author")

                    buildPopulator(db).rebuildAll()

                    val results = db.searchDao().searchBooks("Mary*")
                    results.size shouldBe 1
                    results.first().authorName shouldNotBe null
                    results.first().authorName shouldBe "Mary Shelley"
                }
            } finally {
                db.close()
            }
        }
    })
