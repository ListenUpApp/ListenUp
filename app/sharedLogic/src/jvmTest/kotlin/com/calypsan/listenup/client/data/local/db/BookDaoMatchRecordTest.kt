package com.calypsan.listenup.client.data.local.db

import app.cash.turbine.test
import com.calypsan.listenup.api.dto.match.AppliedChange
import com.calypsan.listenup.api.dto.match.LastMatch
import com.calypsan.listenup.api.dto.match.MetadataSource
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/**
 * [BookDao.observeMatchRecord]: the book's revision beside the match its payload last carried, read from Room so
 * Book Detail's "Undo last match" works offline.
 */
class BookDaoMatchRecordTest :
    FunSpec({
        val match =
            LastMatch(
                receiptId = "r1",
                appliedAt = 1_000L,
                appliedBy = "u1",
                revision = 7L,
                changes = listOf(AppliedChange.Cover(MetadataSource("hardcover", "Hardcover"))),
            )

        test("carries the revision and the stored match, and follows a later write") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val bookDao = db.bookDao()
                    bookDao.upsert(matchRecordBook("b1", revision = 7L, lastMatch = match))

                    bookDao.observeMatchRecord(BookId("b1")).test {
                        awaitItem() shouldBe BookMatchRow(revision = 7L, lastMatch = match)
                        bookDao.upsert(matchRecordBook("b1", revision = 8L, lastMatch = null))
                        awaitItem() shouldBe BookMatchRow(revision = 8L, lastMatch = null)
                        cancelAndIgnoreRemainingEvents()
                    }
                }
            } finally {
                db.close()
            }
        }

        test("emits null for a tombstoned book") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val bookDao = db.bookDao()
                    bookDao.upsert(matchRecordBook("b1", revision = 7L, lastMatch = match))
                    bookDao.softDelete(BookId("b1"), deletedAt = 999L, revision = 8L)

                    bookDao.observeMatchRecord(BookId("b1")).test {
                        awaitItem() shouldBe null
                        cancelAndIgnoreRemainingEvents()
                    }
                }
            } finally {
                db.close()
            }
        }
    })

private fun matchRecordBook(
    id: String,
    revision: Long,
    lastMatch: LastMatch?,
) = BookEntity(
    id = BookId(id),
    libraryId = LibraryId("test-library"),
    folderId = FolderId("test-folder"),
    title = "Book $id",
    totalDuration = 0L,
    revision = revision,
    lastMatch = lastMatch,
    createdAt = Timestamp(1L),
    updatedAt = Timestamp(1L),
)
