package com.calypsan.listenup.client.data.local.db

import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest

/**
 * SECURITY-CRITICAL guard for [BookDao.tombstoneNotIn] under total revocation.
 *
 * When the caller loses ALL access to the book domain, the access-change reconcile calls
 * `tombstoneNotIn(emptySet(), now)`. SQLite evaluates `id NOT IN ()` as TRUE for every row,
 * so all live books are tombstoned — the correct behaviour (total revocation must evict
 * everything). This test pins that: it FAILS if anyone adds an
 * `if (accessibleIds.isEmpty()) return` early-return to the DAO query, which would silently
 * leave revoked content readable (under-prune).
 */
class BookDaoTombstoneNotInTest :
    FunSpec({

        test("tombstoneNotIn(emptySet) tombstones EVERY live book (total revocation)") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val bookDao = db.bookDao()
                    seedBook(bookDao, "b1")
                    seedBook(bookDao, "b2")
                    bookDao.liveIds().toSet() shouldBe setOf("b1", "b2")

                    bookDao.tombstoneNotIn(emptySet(), now = 777L)

                    bookDao.liveIds().shouldBeEmpty()
                    bookDao.getById(BookId("b1"))!!.deletedAt shouldNotBe null
                    bookDao.getById(BookId("b2"))!!.deletedAt shouldNotBe null
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
