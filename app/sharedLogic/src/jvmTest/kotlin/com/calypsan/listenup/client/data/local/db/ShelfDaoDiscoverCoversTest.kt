package com.calypsan.listenup.client.data.local.db

import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/**
 * Pins [ShelfDao.coverRowsFor] against real Room: the cover fields of the books a discovered shelf
 * names, for the ids this device holds — an id never synced here, or tombstoned, yields no row.
 */
class ShelfDaoDiscoverCoversTest :
    FunSpec({
        test("returns cover fields for held live books and skips absent or tombstoned ids") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    seedCoverBook(db.bookDao(), "b1", coverHash = "h1", coverDownloadedAt = Timestamp(5L))
                    seedCoverBook(db.bookDao(), "b2", coverHash = null, coverDownloadedAt = null)
                    seedCoverBook(db.bookDao(), "gone", coverHash = "hg", coverDownloadedAt = Timestamp(5L))
                    db.bookDao().softDelete(BookId("gone"), deletedAt = 9L, revision = 1L)

                    val rows = db.shelfDao().coverRowsFor(listOf("b1", "never-synced", "b2", "gone"))

                    rows.map { it.bookId } shouldContainExactlyInAnyOrder listOf("b1", "b2")
                    val b1 = rows.single { it.bookId == "b1" }
                    b1.coverHash shouldBe "h1"
                    b1.coverDownloadedAt shouldBe Timestamp(5L)
                    rows.single { it.bookId == "b2" }.coverDownloadedAt shouldBe null
                }
            } finally {
                db.close()
            }
        }
    })

private suspend fun seedCoverBook(
    bookDao: BookDao,
    id: String,
    coverHash: String?,
    coverDownloadedAt: Timestamp?,
) {
    bookDao.upsert(
        BookEntity(
            id = BookId(id),
            libraryId = LibraryId("test-library"),
            folderId = FolderId("test-folder"),
            title = "Book $id",
            sortTitle = "Book $id",
            subtitle = null,
            coverHash = coverHash,
            coverDownloadedAt = coverDownloadedAt,
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
