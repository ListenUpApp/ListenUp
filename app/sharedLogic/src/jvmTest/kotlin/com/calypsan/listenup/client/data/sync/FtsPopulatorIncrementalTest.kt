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
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.local.db.SeriesEntity
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/**
 * Integration tests for [FtsPopulator.refreshSince] / [FtsPopulator.snapshotWatermark] /
 * [FtsPopulator.reindexBooks] against a real in-memory [ListenUpDatabase].
 *
 * These prove the revision-watermark delta is correct end-to-end: a targeted update reindexes
 * exactly the changed book (no duplicates, no stale rows left behind), a tombstone disappears
 * from the index, a dimension change (contributor/genre) on an untouched book row is still
 * picked up via the junction-table expansion, the chunked id-scoped reindex survives past
 * SQLite's 999-bound-variable limit, and a no-change delta is a true no-op.
 */
class FtsPopulatorIncrementalTest :
    FunSpec({

        // ========== Seed helpers (revision-aware) ==========

        suspend fun seedBook(
            db: ListenUpDatabase,
            id: String,
            title: String,
            revision: Long = 0,
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
                    revision = revision,
                    createdAt = Timestamp(1L),
                    updatedAt = Timestamp(1L),
                ),
            )
        }

        suspend fun seedContributor(
            db: ListenUpDatabase,
            id: String,
            name: String,
            revision: Long = 0,
        ) {
            db.contributorDao().upsert(
                ContributorEntity(
                    id = ContributorId(id),
                    name = name,
                    description = null,
                    imagePath = null,
                    revision = revision,
                    createdAt = Timestamp(1L),
                    updatedAt = Timestamp(1L),
                ),
            )
        }

        suspend fun linkContributor(
            db: ListenUpDatabase,
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
            db: ListenUpDatabase,
            id: String,
            name: String,
            revision: Long = 0,
        ) {
            db.seriesDao().upsert(
                SeriesEntity(
                    id = SeriesId(id),
                    name = name,
                    description = null,
                    revision = revision,
                    createdAt = Timestamp(1L),
                    updatedAt = Timestamp(1L),
                ),
            )
        }

        suspend fun linkSeries(
            db: ListenUpDatabase,
            bookId: String,
            seriesId: String,
        ) {
            db.bookSeriesDao().insertAll(
                listOf(BookSeriesCrossRef(bookId = BookId(bookId), seriesId = SeriesId(seriesId))),
            )
        }

        suspend fun seedGenre(
            db: ListenUpDatabase,
            id: String,
            name: String,
            revision: Long = 0,
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
                        revision = revision,
                    ),
                ),
            )
        }

        suspend fun linkGenre(
            db: ListenUpDatabase,
            bookId: String,
            genreId: String,
        ) {
            db.genreDao().insertAllBookGenres(
                listOf(BookGenreCrossRef(bookId = BookId(bookId), genreId = genreId)),
            )
        }

        fun buildPopulator(db: ListenUpDatabase) =
            FtsPopulator(
                bookDao = db.bookDao(),
                contributorDao = db.contributorDao(),
                seriesDao = db.seriesDao(),
                searchDao = db.searchDao(),
                transactionRunner = RoomTransactionRunner(db),
            )

        // ========== (a) Targeted update only ==========

        test("refreshSince reindexes only the book that changed, leaving other rows untouched") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    seedBook(db, id = "b1", title = "Alpha Book", revision = 1)
                    seedBook(db, id = "b2", title = "Beta Book", revision = 1)

                    val populator = buildPopulator(db)
                    populator.rebuildAll()

                    val watermark = populator.snapshotWatermark()

                    seedBook(db, id = "b2", title = "Gamma Book", revision = 2)

                    populator.refreshSince(watermark)

                    db.searchDao().searchBooks("Gamma*").isNotEmpty() shouldBe true
                    db.searchDao().searchBooks("Beta*").isEmpty() shouldBe true
                    db.searchDao().searchBooks("Alpha*").isNotEmpty() shouldBe true
                    db.searchDao().countBooksFts() shouldBe 2
                }
            } finally {
                db.close()
            }
        }

        // ========== (b) Removed book disappears ==========

        test("refreshSince removes a tombstoned book from the index") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    seedBook(db, id = "b1", title = "Alpha Book", revision = 1)
                    seedBook(db, id = "b2", title = "Gamma Book", revision = 1)

                    val populator = buildPopulator(db)
                    populator.rebuildAll()

                    val watermark = populator.snapshotWatermark()

                    db.bookDao().softDelete(id = BookId("b2"), deletedAt = 999L, revision = 3)

                    populator.refreshSince(watermark)

                    db.searchDao().searchBooks("Gamma*").isEmpty() shouldBe true
                    db.searchDao().countBooksFts() shouldBe 1
                }
            } finally {
                db.close()
            }
        }

        // ========== (c) Dimension change reflected (contributor) ==========

        test("refreshSince expands a contributor rename to the linked book and rebuilds contributors_fts") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    seedBook(db, id = "b1", title = "Untouched Title", revision = 1)

                    val populator = buildPopulator(db)
                    populator.rebuildAll()

                    val watermark = populator.snapshotWatermark()

                    seedContributor(db, id = "c1", name = "Mary Shelley", revision = 5)
                    linkContributor(db, bookId = "b1", contributorId = "c1", role = "author")

                    populator.refreshSince(watermark)

                    db.searchDao().searchBooks("Shelley*").any { it.book.id == BookId("b1") } shouldBe true
                    db.searchDao().searchContributors("Shelley*").isNotEmpty() shouldBe true
                }
            } finally {
                db.close()
            }
        }

        // ========== (c2) Genre rename reflected ==========

        test("refreshSince expands a genre rename to the linked book") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    seedBook(db, id = "b1", title = "Untouched Title", revision = 1)

                    val populator = buildPopulator(db)
                    populator.rebuildAll()

                    val watermark = populator.snapshotWatermark()

                    seedGenre(db, id = "g1", name = "Cyberpunk", revision = 7)
                    linkGenre(db, bookId = "b1", genreId = "g1")

                    populator.refreshSince(watermark)

                    db.searchDao().searchBooks("Cyberpunk*").any { it.book.id == BookId("b1") } shouldBe true
                }
            } finally {
                db.close()
            }
        }

        // ========== (d) Chunking past the 999-variable limit ==========

        test("reindexBooks completes for a delta larger than SQLite's bound-variable limit") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val bookCount = 1_200
                    for (i in 1..bookCount) {
                        seedBook(db, id = "book%04d".format(i), title = "Book%04d".format(i), revision = 1)
                    }

                    val populator = buildPopulator(db)
                    populator.rebuildAll()

                    val allIds = (1..bookCount).map { "book%04d".format(it) }.toSet()
                    populator.reindexBooks(allIds)

                    db.searchDao().countBooksFts() shouldBe bookCount
                    db.searchDao().searchBooks("Book0777*").isNotEmpty() shouldBe true
                }
            } finally {
                db.close()
            }
        }

        // ========== (f) No-op when nothing changed ==========

        test("refreshSince is a no-op when nothing changed since the watermark") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    seedBook(db, id = "b1", title = "Alpha Book", revision = 1)
                    seedBook(db, id = "b2", title = "Beta Book", revision = 1)

                    val populator = buildPopulator(db)
                    populator.rebuildAll()

                    val watermark = populator.snapshotWatermark()

                    populator.refreshSince(watermark)

                    db.searchDao().countBooksFts() shouldBe 2
                    db.searchDao().searchBooks("Alpha*").isNotEmpty() shouldBe true
                    db.searchDao().searchBooks("Beta*").isNotEmpty() shouldBe true
                }
            } finally {
                db.close()
            }
        }
    })
