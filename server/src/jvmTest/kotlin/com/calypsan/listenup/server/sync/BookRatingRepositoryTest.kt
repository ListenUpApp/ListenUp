@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookRatingSyncPayload
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * Tests for [BookRatingRepository] — the `book_ratings` syncable repository. Covers the
 * "existing row's id wins" natural-pair rule, per-listener isolation, tombstone/revive on
 * clear + re-rate, the account-deletion sweep, and tombstone minimization on the wire.
 */
class BookRatingRepositoryTest :
    FunSpec({
        fun rating(
            userId: String,
            halfStars: Int,
            candidate: String = "cand-$userId",
        ) = BookRatingSyncPayload(
            id = candidate,
            bookId = "book1",
            userId = userId,
            halfStars = halfStars,
            note = null,
            ratedAt = 1_000L,
            updatedAt = 1_000L,
            revision = 0L,
        )

        test("a first rating stores the candidate id; re-rating keeps that id and overwrites the stars") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                sql.seedTestUser("u1")
                val repo = BookRatingRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry(), driver = driver)
                runTest {
                    repo.upsert(rating("u1", 6, candidate = "first")).shouldBeInstanceOf<AppResult.Success<*>>()
                    val second = repo.upsert(rating("u1", 9, candidate = "second"))

                    val stored = second.shouldBeInstanceOf<AppResult.Success<BookRatingSyncPayload>>().data
                    stored.id shouldBe "first"
                    stored.halfStars shouldBe 9
                }
            }
        }

        test("two listeners each keep their own row for the same book") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                sql.seedTestUser("u1")
                sql.seedTestUser("u2")
                val repo = BookRatingRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry(), driver = driver)
                runTest {
                    repo.upsert(rating("u1", 6))
                    repo.upsert(rating("u2", 10))

                    repo.findForBook("book1").associate { it.userId to it.halfStars } shouldBe mapOf("u1" to 6, "u2" to 10)
                }
            }
        }

        test("clearing tombstones only the caller's row, and re-rating revives it") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                sql.seedTestUser("u1")
                sql.seedTestUser("u2")
                val repo = BookRatingRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry(), driver = driver)
                runTest {
                    repo.upsert(rating("u1", 6))
                    repo.upsert(rating("u2", 8))

                    repo.clear(bookId = "book1", userId = "u1").shouldBeInstanceOf<AppResult.Success<*>>()
                    repo.findForBook("book1").map { it.userId } shouldBe listOf("u2")

                    repo.upsert(rating("u1", 4))
                    repo
                        .findForBook("book1")
                        .first { it.userId == "u1" }
                        .deletedAt
                        .shouldBeNull()
                }
            }
        }

        test("a user's ratings are tombstoned together") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                sql.seedTestBook("book2")
                sql.seedTestUser("u1")
                val repo = BookRatingRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry(), driver = driver)
                runTest {
                    repo.upsert(rating("u1", 6))
                    repo.upsert(rating("u1", 6).copy(id = "other", bookId = "book2"))

                    repo.softDeleteAllForUser("u1") shouldBe 2
                    repo.findForBook("book1") shouldBe emptyList()
                }
            }
        }

        test("a tombstone crossing the wire carries neither the book nor the listener") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                sql.seedTestUser("u1")
                val repo = BookRatingRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry(), driver = driver)
                runTest {
                    repo.upsert(rating("u1", 6))
                    repo.clear("book1", "u1")

                    val tombstone = repo.pullSince(userId = null, cursor = 0L, limit = 10).items.single()
                    tombstone.bookId shouldBe ""
                    tombstone.userId shouldBe ""
                }
            }
        }
    })
