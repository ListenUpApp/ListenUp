package com.calypsan.listenup.client.data.local.db

import app.cash.turbine.test
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/**
 * Pins the SQL of [CachedActiveSessionDao.observeWithBooks] against real Room: the roster joins the
 * local library in one query, drops rows whose book is absent or tombstoned, names the book's author,
 * orders live rows first, and re-emits when the library changes — not only when the roster does.
 */
class CachedActiveSessionDaoTest :
    FunSpec({

        test("joins each row to its book and its author, live rows first, newest first within each half") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    seedBook(db.bookDao(), "b1", title = "The Way of Kings")
                    seedBook(db.bookDao(), "b2", title = "Elantris")
                    seedAuthor(db, bookId = "b1", name = "Brandon Sanderson")
                    db.cachedActiveSessionDao().replaceAll(
                        listOf(
                            row(userId = "recent", bookId = "b2", isLive = false, lastActiveAtMs = 900L),
                            row(userId = "live-old", bookId = "b1", isLive = true, lastActiveAtMs = 100L),
                            row(userId = "live-new", bookId = "b2", isLive = true, lastActiveAtMs = 500L),
                        ),
                    )

                    db.cachedActiveSessionDao().observeWithBooks().test {
                        val rows = awaitItem()
                        rows.map { it.session.userId } shouldBe listOf("live-new", "live-old", "recent")
                        rows.first { it.session.userId == "live-old" }.bookTitle shouldBe "The Way of Kings"
                        rows.first { it.session.userId == "live-old" }.bookAuthorName shouldBe "Brandon Sanderson"
                        rows.first { it.session.userId == "recent" }.bookAuthorName shouldBe null
                        cancelAndIgnoreRemainingEvents()
                    }
                }
            } finally {
                db.close()
            }
        }

        test("drops rows whose book is absent or tombstoned") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    seedBook(db.bookDao(), "gone")
                    db.bookDao().softDelete(BookId("gone"), deletedAt = 999L, revision = 1L)
                    db.cachedActiveSessionDao().replaceAll(
                        listOf(row(userId = "a", bookId = "gone"), row(userId = "b", bookId = "never-synced")),
                    )

                    db.cachedActiveSessionDao().observeWithBooks().test {
                        awaitItem().shouldBeEmpty()
                        cancelAndIgnoreRemainingEvents()
                    }
                }
            } finally {
                db.close()
            }
        }

        test("re-emits when the book arrives after the roster row") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    db.cachedActiveSessionDao().replaceAll(listOf(row(userId = "a", bookId = "late")))

                    db.cachedActiveSessionDao().observeWithBooks().test {
                        awaitItem().shouldBeEmpty()
                        seedBook(db.bookDao(), "late", title = "Late")
                        awaitItem().single().bookTitle shouldBe "Late"
                        cancelAndIgnoreRemainingEvents()
                    }
                }
            } finally {
                db.close()
            }
        }
    })

private fun row(
    userId: String,
    bookId: String,
    isLive: Boolean = true,
    lastActiveAtMs: Long = 1L,
) = CachedActiveSessionEntity(
    userId = userId,
    displayName = "User $userId",
    avatarType = "auto",
    bookId = bookId,
    lastActiveAtMs = lastActiveAtMs,
    isLive = isLive,
    observedAt = 1L,
)

private suspend fun seedAuthor(
    db: ListenUpDatabase,
    bookId: String,
    name: String,
) {
    val contributorId = "c-$bookId"
    db.contributorDao().upsert(
        ContributorEntity(
            id = ContributorId(contributorId),
            name = name,
            description = null,
            imagePath = null,
            createdAt = Timestamp(1L),
            updatedAt = Timestamp(1L),
        ),
    )
    db.bookContributorDao().insertAll(
        listOf(BookContributorCrossRef(bookId = BookId(bookId), contributorId = ContributorId(contributorId), role = "author")),
    )
}

private suspend fun seedBook(
    bookDao: BookDao,
    id: String,
    title: String = "Book $id",
) {
    bookDao.upsert(
        BookEntity(
            id = BookId(id),
            libraryId = LibraryId("test-library"),
            folderId = FolderId("test-folder"),
            title = title,
            sortTitle = title,
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
