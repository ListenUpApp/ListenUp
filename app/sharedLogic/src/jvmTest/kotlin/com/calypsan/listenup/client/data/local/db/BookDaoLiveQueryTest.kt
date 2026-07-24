package com.calypsan.listenup.client.data.local.db

import app.cash.turbine.test
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.Timestamp
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/**
 * Pins that the live read paths exclude tombstoned (soft-deleted) books.
 *
 * Regression guard for the case where [BookDao.observeAllWithContributors]
 * (library list + series view) and the FTS feeder query returned server-deleted books,
 * despite the DAO KDoc promising `deletedAt IS NULL` filtering.
 */
class BookDaoLiveQueryTest :
    FunSpec({

        test("observeAllWithContributors excludes soft-deleted books") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val bookDao = db.bookDao()
                    seedBook(bookDao, "b1")
                    seedBook(bookDao, "b2")
                    bookDao.softDelete(BookId("b2"), deletedAt = 999L, revision = 1L)

                    bookDao.observeAllWithContributors().test {
                        awaitItem().map { it.book.id.value } shouldBe listOf("b1")
                        cancelAndIgnoreRemainingEvents()
                    }
                }
            } finally {
                db.close()
            }
        }

        test("observeAllWithContributors still emits live books") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val bookDao = db.bookDao()
                    seedBook(bookDao, "b1")
                    seedBook(bookDao, "b2")

                    bookDao.observeAllWithContributors().test {
                        awaitItem().map { it.book.id.value } shouldBe listOf("b1", "b2")
                        cancelAndIgnoreRemainingEvents()
                    }
                }
            } finally {
                db.close()
            }
        }

        test("getAllLive excludes soft-deleted books") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val bookDao = db.bookDao()
                    seedBook(bookDao, "b1")
                    seedBook(bookDao, "b2")
                    bookDao.softDelete(BookId("b2"), deletedAt = 999L, revision = 1L)

                    bookDao.getAllLive().map { it.id.value }.toSet() shouldBe setOf("b1")
                }
            } finally {
                db.close()
            }
        }

        test("getAllLive still returns live books") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val bookDao = db.bookDao()
                    seedBook(bookDao, "b1")
                    seedBook(bookDao, "b2")

                    bookDao.getAllLive().map { it.id.value }.toSet() shouldBe setOf("b1", "b2")
                }
            } finally {
                db.close()
            }
        }

        test("observeBySeriesIdWithContributors excludes soft-deleted books") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val bookDao = db.bookDao()
                    val seriesDao = db.seriesDao()
                    val bookSeriesDao = db.bookSeriesDao()

                    seedBook(bookDao, "b1")
                    seedBook(bookDao, "b2")
                    liveQuerySeedSeries(seriesDao, "s1")
                    liveQueryLinkBookToSeries(bookSeriesDao, bookId = "b1", seriesId = "s1", sequence = "1")
                    liveQueryLinkBookToSeries(bookSeriesDao, bookId = "b2", seriesId = "s1", sequence = "2")
                    bookDao.softDelete(BookId("b2"), deletedAt = 999L, revision = 1L)

                    bookDao.observeBySeriesIdWithContributors("s1").test {
                        awaitItem().map { it.book.id.value } shouldBe listOf("b1")
                        cancelAndIgnoreRemainingEvents()
                    }
                }
            } finally {
                db.close()
            }
        }

        test("observeByContributorAndRole excludes soft-deleted books") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val bookDao = db.bookDao()
                    val contributorDao = db.contributorDao()
                    val bookContributorDao = db.bookContributorDao()

                    seedBook(bookDao, "b1")
                    seedBook(bookDao, "b2")
                    liveQuerySeedContributor(contributorDao, "c1")
                    liveQueryLinkBookToContributor(bookContributorDao, bookId = "b1", contributorId = "c1", role = "author")
                    liveQueryLinkBookToContributor(bookContributorDao, bookId = "b2", contributorId = "c1", role = "author")
                    bookDao.softDelete(BookId("b2"), deletedAt = 999L, revision = 1L)

                    bookDao.observeByContributorAndRole("c1", "author").test {
                        awaitItem().map { it.book.id.value } shouldBe listOf("b1")
                        cancelAndIgnoreRemainingEvents()
                    }
                }
            } finally {
                db.close()
            }
        }

        test("observeRandomUnstartedBooks excludes soft-deleted books") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val bookDao = db.bookDao()
                    seedBook(bookDao, "b1")
                    seedBook(bookDao, "b2")
                    bookDao.softDelete(BookId("b2"), deletedAt = 999L, revision = 1L)

                    bookDao.observeRandomUnstartedBooks(limit = 10).test {
                        awaitItem().map { it.id.value }.toSet() shouldBe setOf("b1")
                        cancelAndIgnoreRemainingEvents()
                    }
                }
            } finally {
                db.close()
            }
        }

        test("observeRecentlyAddedWithAuthor excludes soft-deleted books") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val bookDao = db.bookDao()
                    seedBook(bookDao, "b1")
                    seedBook(bookDao, "b2")
                    bookDao.softDelete(BookId("b2"), deletedAt = 999L, revision = 1L)

                    bookDao.observeRecentlyAddedWithAuthor(limit = 10).test {
                        awaitItem().map { it.id.value }.toSet() shouldBe setOf("b1")
                        cancelAndIgnoreRemainingEvents()
                    }
                }
            } finally {
                db.close()
            }
        }

        test("observeUnstartedCandidatesWithSeries excludes soft-deleted books") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val bookDao = db.bookDao()
                    seedBook(bookDao, "b1")
                    seedBook(bookDao, "b2")
                    bookDao.softDelete(BookId("b2"), deletedAt = 999L, revision = 1L)

                    bookDao.observeUnstartedCandidatesWithSeries().test {
                        awaitItem().map { it.id.value }.toSet() shouldBe setOf("b1")
                        cancelAndIgnoreRemainingEvents()
                    }
                }
            } finally {
                db.close()
            }
        }
    })

private suspend fun liveQuerySeedSeries(
    seriesDao: SeriesDao,
    id: String,
) {
    seriesDao.upsert(
        SeriesEntity(
            id = SeriesId(id),
            name = "Series $id",
            description = null,
            createdAt = Timestamp(1L),
            updatedAt = Timestamp(1L),
        ),
    )
}

private suspend fun liveQueryLinkBookToSeries(
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

private suspend fun liveQuerySeedContributor(
    contributorDao: ContributorDao,
    id: String,
) {
    contributorDao.upsert(
        ContributorEntity(
            id = ContributorId(id),
            name = "Contributor $id",
            description = null,
            imagePath = null,
            createdAt = Timestamp(1L),
            updatedAt = Timestamp(1L),
        ),
    )
}

private suspend fun liveQueryLinkBookToContributor(
    bookContributorDao: BookContributorDao,
    bookId: String,
    contributorId: String,
    role: String,
) {
    bookContributorDao.insertAll(
        listOf(
            BookContributorCrossRef(
                bookId = BookId(bookId),
                contributorId = ContributorId(contributorId),
                role = role,
            ),
        ),
    )
}

private suspend fun seedBook(
    bookDao: BookDao,
    id: String,
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
            createdAt = Timestamp(1L),
            updatedAt = Timestamp(1L),
        ),
    )
}
