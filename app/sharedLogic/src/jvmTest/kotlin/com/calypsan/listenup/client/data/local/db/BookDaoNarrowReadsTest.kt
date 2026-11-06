package com.calypsan.listenup.client.data.local.db

import app.cash.turbine.test
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.stubImageStorage
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.Timestamp
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/**
 * The narrow reads behind the always-on probes answer exactly what the full Book Detail read would: the same
 * existence (a tombstone still exists), the same duration, and the same first series, in the same order.
 */
class BookDaoNarrowReadsTest :
    FunSpec({
        test("observeRowExists: a live row and a tombstone exist, an absent id does not, and a late row appears") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val bookDao = db.bookDao()
                    narrowSeedBook(bookDao, "live")
                    narrowSeedBook(bookDao, "dead")
                    bookDao.softDelete(BookId("dead"), deletedAt = 999L, revision = 1L)

                    bookDao.observeRowExists(BookId("live")).first() shouldBe true
                    bookDao.observeRowExists(BookId("dead")).first() shouldBe true
                    bookDao.observeRowExists(BookId("dead")).first() shouldBe
                        (bookDao.observeByIdWithContributors(BookId("dead")).first() != null)
                    bookDao.observeRowExists(BookId("absent")).first() shouldBe false

                    bookDao.observeRowExists(BookId("late")).test {
                        awaitItem() shouldBe false
                        narrowSeedBook(bookDao, "late")
                        awaitItem() shouldBe true
                        cancelAndIgnoreRemainingEvents()
                    }
                }
            } finally {
                db.close()
            }
        }

        test("observeTotalDuration follows the row, and is null for an absent id") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val bookDao = db.bookDao()
                    narrowSeedBook(bookDao, "b1", durationMs = 12_000L)

                    bookDao.observeTotalDuration(BookId("absent")).first() shouldBe null
                    bookDao.observeTotalDuration(BookId("b1")).test {
                        awaitItem() shouldBe 12_000L
                        narrowSeedBook(bookDao, "b1", durationMs = 34_000L)
                        awaitItem() shouldBe 34_000L
                        cancelAndIgnoreRemainingEvents()
                    }
                }
            } finally {
                db.close()
            }
        }

        test("observeFirstSeriesId equals the first series of the full detail read, in the same order") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val bookDao = db.bookDao()
                    val seriesDao = db.seriesDao()
                    val bookSeriesDao = db.bookSeriesDao()
                    narrowSeedBook(bookDao, "b1")
                    narrowSeedBook(bookDao, "b2")
                    narrowSeedBook(bookDao, "b3")
                    // Inserted against alphabetical order, so an index-order read and a rowid-order read disagree.
                    listOf("s-zeta", "s-mid", "s-alpha").forEach { narrowSeedSeries(seriesDao, it) }
                    listOf("s-zeta", "s-alpha", "s-mid").forEach { narrowLink(bookSeriesDao, "b1", it) }
                    listOf("s-mid", "s-zeta").forEach { narrowLink(bookSeriesDao, "b2", it) }

                    for (id in listOf("b1", "b2", "b3", "absent")) {
                        val full =
                            bookDao
                                .observeByIdWithContributors(BookId(id))
                                .first()
                                ?.toDetail(stubImageStorage(), emptyList(), emptyList(), emptyList())
                                ?.series
                                ?.firstOrNull()
                                ?.seriesId
                        bookDao.observeFirstSeriesId(BookId(id)).first() shouldBe full
                    }
                    bookDao.observeFirstSeriesId(BookId("b1")).first() shouldBe "s-zeta"
                    bookDao.observeFirstSeriesId(BookId("b3")).first() shouldBe null
                }
            } finally {
                db.close()
            }
        }
    })

private suspend fun narrowSeedSeries(
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

private suspend fun narrowLink(
    bookSeriesDao: BookSeriesDao,
    bookId: String,
    seriesId: String,
) {
    bookSeriesDao.insert(BookSeriesCrossRef(bookId = BookId(bookId), seriesId = SeriesId(seriesId), sequence = 1.0))
}

private suspend fun narrowSeedBook(
    bookDao: BookDao,
    id: String,
    durationMs: Long = 0L,
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
            totalDuration = durationMs,
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
