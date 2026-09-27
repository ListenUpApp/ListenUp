@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookRatingSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * Tests for [BookRatingRepository] — the `book_ratings` syncable repository. Covers the
 * "existing row's id wins" natural-pair rule, per-listener isolation, tombstone/revive on
 * clear + re-rate, the account-deletion sweep, tombstone minimization on the wire, the
 * candidate-id guards on a fresh insert, and repeat-clear idempotency.
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
                val bus = ChangeBus()
                val repo = BookRatingRepository(db = sql, bus = bus, registry = SyncRegistry(), driver = driver)
                runTest {
                    repo.upsert(rating("u1", 6)).shouldBeInstanceOf<AppResult.Success<*>>()
                    repo.upsert(rating("u1", 6).copy(id = "other", bookId = "book2")).shouldBeInstanceOf<AppResult.Success<*>>()

                    // Subscribe after the two setup Created events; the sweep must publish exactly
                    // one Deleted per tombstoned row.
                    val sub =
                        async {
                            bus
                                .subscribe()
                                .drop(2)
                                .take(2)
                                .toList()
                        }
                    advanceUntilIdle()

                    repo.softDeleteAllForUser("u1") shouldBe 2

                    val deleted = sub.await()
                    deleted shouldHaveSize 2
                    deleted.forEach { it.event.shouldBeInstanceOf<SyncEvent.Deleted>() }
                    deleted.map { it.event.id }.toSet() shouldBe setOf("cand-u1", "other")

                    repo.findForBook("book1") shouldBe emptyList()
                    repo.findForBook("book2") shouldBe emptyList()
                }
            }
        }

        test("a tombstone crossing the wire carries neither the book, the listener, nor the note") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                sql.seedTestUser("u1")
                val repo = BookRatingRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry(), driver = driver)
                runTest {
                    val stored =
                        repo
                            .upsert(rating("u1", 6, candidate = "r1").copy(note = "Loved the ending."))
                            .shouldBeInstanceOf<AppResult.Success<BookRatingSyncPayload>>()
                            .data
                    repo.clear("book1", "u1")

                    val tombstone = repo.pullSince(userId = null, cursor = 0L, limit = 10).items.single()
                    tombstone.id shouldBe stored.id
                    tombstone.bookId shouldBe ""
                    tombstone.userId shouldBe ""
                    tombstone.note.shouldBeNull()
                }
            }
        }

        // ── candidate-id guards (item 1) ────────────────────────────────────────

        test("clearing a pair with no row never touches an unrelated row whose id is blank") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                sql.seedTestBook("book2")
                sql.seedTestUser("u1")
                sql.seedTestUser("u2")
                val repo = BookRatingRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry(), driver = driver)
                runTest {
                    // Legacy/malformed data: a row with a blank id, for a DIFFERENT pair than the one
                    // about to be cleared. upsert() itself now refuses to create such a row (see the
                    // blank-candidate guard test below); this simulates one that pre-dates the guard.
                    sql.transaction {
                        sql.bookRatingsQueries.insert(
                            id = "",
                            book_id = "book2",
                            user_id = "u2",
                            half_stars = 6,
                            note = null,
                            rated_at = 1_000L,
                            created_at = 1_000L,
                            updated_at = 1_000L,
                            revision = 0L,
                            client_op_id = null,
                        )
                    }

                    // u1 never rated book1 — clearing it must be a no-op, not a fallback onto the
                    // blank-id row that happens to exist for book2/u2.
                    repo.clear(bookId = "book1", userId = "u1").shouldBeInstanceOf<AppResult.Success<*>>()

                    repo
                        .findForBook("book2")
                        .single()
                        .deletedAt
                        .shouldBeNull()
                }
            }
        }

        test("a blank candidate id on a fresh pair is refused") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                sql.seedTestUser("u1")
                val repo = BookRatingRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry(), driver = driver)
                runTest {
                    repo.upsert(rating("u1", 6, candidate = "")).shouldBeInstanceOf<AppResult.Failure>()

                    repo.findForBook("book1") shouldBe emptyList()
                }
            }
        }

        test("a colliding candidate id is refused and leaves the original row untouched") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                sql.seedTestUser("u1")
                sql.seedTestUser("u2")
                val repo = BookRatingRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry(), driver = driver)
                runTest {
                    val original =
                        repo
                            .upsert(rating("u1", 6, candidate = "shared"))
                            .shouldBeInstanceOf<AppResult.Success<BookRatingSyncPayload>>()
                            .data

                    // u2 has no row yet, but asks to mint the SAME id u1's row already owns.
                    repo.upsert(rating("u2", 10, candidate = "shared")).shouldBeInstanceOf<AppResult.Failure>()

                    repo.findForBook("book1") shouldBe listOf(original)
                }
            }
        }

        // ── repeat clear (item 2) ────────────────────────────────────────────────

        test("clearing twice leaves the row's revision unchanged and emits no second Deleted event") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                sql.seedTestUser("u1")
                sql.seedTestUser("u2")
                val bus = ChangeBus()
                val repo = BookRatingRepository(db = sql, bus = bus, registry = SyncRegistry(), driver = driver)
                runTest {
                    // Subscribe before any write: ChangeBus replays its whole history to a new
                    // subscriber, so dropping the first two (the Created, then the first clear's
                    // Deleted) leaves exactly the next thing published — which must be the unrelated
                    // marker's Created, proving the second (no-op) clear published nothing in between.
                    val sub = async { bus.subscribe().drop(2).first() }
                    advanceUntilIdle()

                    repo.upsert(rating("u1", 6)).shouldBeInstanceOf<AppResult.Success<*>>()
                    repo.clear("book1", "u1").shouldBeInstanceOf<AppResult.Success<*>>()
                    val afterFirstClear = repo.pullSince(userId = null, cursor = 0L, limit = 10).items.single()

                    repo.clear("book1", "u1").shouldBeInstanceOf<AppResult.Success<*>>()
                    repo.upsert(rating("u2", 8, candidate = "marker"))

                    sub.await().event.id shouldBe "marker"

                    val afterSecondClear =
                        repo
                            .pullSince(userId = null, cursor = 0L, limit = 10)
                            .items
                            .first { it.id == afterFirstClear.id }
                    afterSecondClear.revision shouldBe afterFirstClear.revision
                }
            }
        }
    })
