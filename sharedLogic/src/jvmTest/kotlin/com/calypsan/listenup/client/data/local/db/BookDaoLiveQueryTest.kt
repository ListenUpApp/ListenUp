package com.calypsan.listenup.client.data.local.db

import app.cash.turbine.test
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/**
 * Pins that the live read paths exclude tombstoned (soft-deleted) books.
 *
 * Regression guard for the audit finding where [BookDao.observeAllWithContributors]
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
    })

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
