package com.calypsan.listenup.client.data.local.db

import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest

/**
 * Contract guard for [BookDao.tombstoneByIds] — the chunked access-change prune primitive.
 *
 * The composed handler computes the doomed set (`liveIds - accessible`) and tombstones it in
 * bounded chunks. This pins the DAO leg: [BookDao.tombstoneByIds] tombstones exactly the ids it is
 * handed and nothing else, so a total revocation (handler passes every live id) evicts everything
 * while an empty chunk (accessible = everything) touches nothing.
 */
class BookDaoTombstoneByIdsTest :
    FunSpec({

        test("tombstoneByIds tombstones exactly the given books, leaving the rest live") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val bookDao = db.bookDao()
                    seedBook(bookDao, "b1")
                    seedBook(bookDao, "b2")
                    seedBook(bookDao, "b3")
                    bookDao.liveIds().toSet() shouldBe setOf("b1", "b2", "b3")

                    bookDao.tombstoneByIds(listOf("b1", "b2"), now = 777L)

                    bookDao.liveIds() shouldBe listOf("b3")
                    bookDao.getById(BookId("b1"))!!.deletedAt shouldNotBe null
                    bookDao.getById(BookId("b2"))!!.deletedAt shouldNotBe null
                    bookDao.getById(BookId("b3"))!!.deletedAt shouldBe null
                }
            } finally {
                db.close()
            }
        }

        test("tombstoneByIds(emptyList) tombstones nothing (accessible = everything)") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val bookDao = db.bookDao()
                    seedBook(bookDao, "b1")
                    seedBook(bookDao, "b2")

                    bookDao.tombstoneByIds(emptyList(), now = 777L)

                    bookDao.liveIds().toSet() shouldBe setOf("b1", "b2")
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
