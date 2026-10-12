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
 * Pins the cover columns of [ActivityDao.observeRecent]: a row about a book carries that book's cover
 * hash and download stamp (it used to select `NULL`), and a row about no book carries neither.
 */
class ActivityDaoCoverTest :
    FunSpec({
        test("a book activity selects its book's cover; a joined row selects none") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    db.bookDao().upsert(
                        BookEntity(
                            id = BookId("b1"),
                            libraryId = LibraryId("test-library"),
                            folderId = FolderId("test-folder"),
                            title = "Dune",
                            sortTitle = "Dune",
                            subtitle = null,
                            coverHash = "h1",
                            coverDownloadedAt = Timestamp(5L),
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
                    db.activityDao().upsert(activity(id = "a1", type = "finished_book", bookId = "b1", occurredAt = 2L))
                    db.activityDao().upsert(activity(id = "a2", type = "user_joined", bookId = null, occurredAt = 1L))

                    db.activityDao().observeRecent(10).test {
                        val rows = awaitItem()
                        val book = rows.single { it.id == "a1" }
                        book.bookCoverHash shouldBe "h1"
                        book.bookCoverDownloadedAt shouldBe Timestamp(5L)
                        val joined = rows.single { it.id == "a2" }
                        joined.bookCoverHash shouldBe null
                        joined.bookCoverDownloadedAt shouldBe null
                        cancelAndIgnoreRemainingEvents()
                    }
                }
            } finally {
                db.close()
            }
        }
    })

private fun activity(
    id: String,
    type: String,
    bookId: String?,
    occurredAt: Long,
) = ActivityEntity(
    id = id,
    userId = "u1",
    type = type,
    occurredAt = occurredAt,
    bookId = bookId,
    isReread = false,
    durationMs = 0L,
    milestoneValue = 0,
    milestoneUnit = null,
    shelfId = null,
    shelfName = null,
)
