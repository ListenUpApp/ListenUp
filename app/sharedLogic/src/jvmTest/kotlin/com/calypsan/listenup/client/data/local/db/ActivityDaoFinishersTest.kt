package com.calypsan.listenup.client.data.local.db

import app.cash.turbine.test
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/**
 * The just-finished card's social line (spec §2.3) reads the local activities mirror: everyone but you who finished
 * this book, once each, most recent finish first. Works offline by construction.
 */
class ActivityDaoFinishersTest :
    FunSpec({
        test("other finishers of a book, once each, newest finish first; not you, not tombstoned, not other books") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    db.publicProfileDao().upsert(profile("u2", "Maya"))
                    db.publicProfileDao().upsert(profile("u3", "Tom"))
                    listOf(
                        finished("a1", user = "u2", book = "b1", at = 10L),
                        finished("a2", user = "u3", book = "b1", at = 20L),
                        finished("a3", user = "u3", book = "b1", at = 30L),
                        finished("a4", user = "me", book = "b1", at = 40L),
                        finished("a5", user = "u5", book = "b2", at = 50L),
                        finished("a6", user = "u6", book = "b1", at = 60L).copy(deletedAt = 61L),
                        finished("a7", user = "u7", book = "b1", at = 70L).copy(type = "started_book"),
                        finished("a8", user = "u8", book = "b1", at = 5L),
                    ).forEach { db.activityDao().upsert(it) }

                    db.activityDao().observeFinishersOf(bookId = "b1", excludeUserId = "me").test {
                        awaitItem() shouldBe
                            listOf(
                                BookFinisherRow(userId = "u3", displayName = "Tom", finishedAt = 30L),
                                BookFinisherRow(userId = "u2", displayName = "Maya", finishedAt = 10L),
                                BookFinisherRow(userId = "u8", displayName = null, finishedAt = 5L),
                            )
                        cancelAndIgnoreRemainingEvents()
                    }
                }
            } finally {
                db.close()
            }
        }
    })

private fun finished(
    id: String,
    user: String,
    book: String,
    at: Long,
) = ActivityEntity(
    id = id,
    userId = user,
    type = "finished_book",
    occurredAt = at,
    bookId = book,
    isReread = false,
    durationMs = 0L,
    milestoneValue = 0,
    milestoneUnit = null,
    shelfId = null,
    shelfName = null,
)

private fun profile(
    id: String,
    name: String,
) = PublicProfileEntity(
    id = id,
    displayName = name,
    avatarType = "auto",
    totalSecondsAllTime = 0L,
    totalSecondsLast7Days = 0L,
    totalSecondsLast30Days = 0L,
    totalSecondsLast365Days = 0L,
    booksFinished = 0,
    currentStreakDays = 0,
    longestStreakDays = 0,
)
