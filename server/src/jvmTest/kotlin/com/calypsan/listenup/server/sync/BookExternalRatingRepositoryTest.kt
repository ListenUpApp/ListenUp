@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.ExternalRatingSource
import com.calypsan.listenup.api.sync.ExternalRatingSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * Tests for [BookExternalRatingRepository] — the `book_external_ratings` syncable repository.
 * Covers the server-minted "existing row's id wins" identity on [BookExternalRatingRepository.recordFetch],
 * the per-source enable/disable sweep, the nightly sweep's candidate ordering, and tombstone
 * minimization. Per-source health lives in `RatingSourceSettings`, not on this repository — see
 * `com.calypsan.listenup.server.ratings.ExternalRatingsFetcherTest` for its coverage.
 */
class BookExternalRatingRepositoryTest :
    FunSpec({

        test("a first fetch inserts; re-fetching the same book/source updates in place and keeps the id") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1", asin = "B001")
                val repo =
                    BookExternalRatingRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry(), driver = driver)
                runTest {
                    val first =
                        repo
                            .recordFetch(
                                bookId = "book1",
                                source = ExternalRatingSource.AUDIBLE,
                                average = 4.2,
                                count = 100,
                                region = "us",
                                fetchedAt = 1_000L,
                            ).shouldBeInstanceOf<AppResult.Success<ExternalRatingSyncPayload>>()
                            .data
                    first.average shouldBe 4.2
                    first.count shouldBe 100
                    first.enabled shouldBe true

                    val second =
                        repo
                            .recordFetch(
                                bookId = "book1",
                                source = ExternalRatingSource.AUDIBLE,
                                average = 4.6,
                                count = 150,
                                region = "us",
                                fetchedAt = 2_000L,
                            ).shouldBeInstanceOf<AppResult.Success<ExternalRatingSyncPayload>>()
                            .data

                    second.id shouldBe first.id
                    second.average shouldBe 4.6
                    second.count shouldBe 150

                    repo.findForBook("book1") shouldBe listOf(second)
                }
            }
        }

        test("two sources of the same book each keep their own row") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1", asin = "B001")
                val repo =
                    BookExternalRatingRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry(), driver = driver)
                runTest {
                    repo.recordFetch("book1", ExternalRatingSource.AUDIBLE, 4.2, 100, "us", 1_000L)
                    repo.recordFetch("book1", ExternalRatingSource.HARDCOVER, 3.9, 40, null, 1_000L)

                    repo.findForBook("book1").associate { it.source to it.count } shouldBe
                        mapOf(ExternalRatingSource.AUDIBLE to 100, ExternalRatingSource.HARDCOVER to 40)
                }
            }
        }

        test("setSourceEnabled(false) flips every row of that source and emits one Updated per row, leaving other sources untouched") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1", asin = "B001")
                sql.seedTestBook("book2", asin = "B002")
                val bus = ChangeBus()
                val repo = BookExternalRatingRepository(db = sql, bus = bus, registry = SyncRegistry(), driver = driver)
                runTest {
                    repo.recordFetch("book1", ExternalRatingSource.AUDIBLE, 4.2, 100, "us", 1_000L)
                    repo.recordFetch("book2", ExternalRatingSource.AUDIBLE, 3.5, 20, "us", 1_000L)
                    repo.recordFetch("book1", ExternalRatingSource.HARDCOVER, 4.8, 5, null, 1_000L)

                    // Subscribe after the three setup Created events; setSourceEnabled must publish
                    // exactly one Updated per affected row.
                    val sub =
                        async {
                            bus
                                .subscribe()
                                .drop(3)
                                .take(2)
                                .toList()
                        }
                    advanceUntilIdle()

                    val flipped = repo.setSourceEnabled(ExternalRatingSource.AUDIBLE, enabled = false)
                    flipped shouldBe 2

                    val updates = sub.await()
                    updates shouldHaveSize 2
                    updates.forEach { it.event.shouldBeInstanceOf<SyncEvent.Updated<*>>() }

                    repo.findForBook("book1").first { it.source == ExternalRatingSource.AUDIBLE }.enabled shouldBe false
                    repo.findForBook("book2").first { it.source == ExternalRatingSource.AUDIBLE }.enabled shouldBe false
                    repo.findForBook("book1").first { it.source == ExternalRatingSource.HARDCOVER }.enabled shouldBe true
                }
            }
        }

        test("sweepCandidates orders never-fetched first, then oldest fetched_at, and skips books with no ASIN and removed books") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("no-asin")
                sql.seedTestBook("never-fetched", asin = "B-NEVER")
                sql.seedTestBook("old-fetch", asin = "B-OLD")
                sql.seedTestBook("new-fetch", asin = "B-NEW")
                sql.seedTestBook("removed", asin = "B-REMOVED")
                val repo =
                    BookExternalRatingRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry(), driver = driver)
                runTest {
                    repo.recordFetch("old-fetch", ExternalRatingSource.AUDIBLE, 4.0, 10, "us", fetchedAt = 1_000L)
                    repo.recordFetch("new-fetch", ExternalRatingSource.AUDIBLE, 4.0, 10, "us", fetchedAt = 5_000L)
                    repo.recordFetch("removed", ExternalRatingSource.AUDIBLE, 4.0, 10, "us", fetchedAt = 500L)

                    sql.transaction {
                        sql.booksQueries.softDeleteById(
                            revision = 999L,
                            updated_at = 9_000L,
                            deleted_at = 9_000L,
                            client_op_id = null,
                            id = "removed",
                        )
                    }

                    repo.sweepCandidates(limit = 10) shouldBe listOf("never-fetched", "old-fetch", "new-fetch")
                }
            }
        }

        test("countBooksWithAsin counts only live books carrying a non-blank ASIN") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("no-asin")
                sql.seedTestBook("blank-asin", asin = "")
                sql.seedTestBook("has-asin", asin = "B-1")
                val repo =
                    BookExternalRatingRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry(), driver = driver)
                runTest {
                    repo.countBooksWithAsin() shouldBe 1L
                }
            }
        }

        test("a tombstone crossing the wire carries no bookId") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1", asin = "B001")
                val repo =
                    BookExternalRatingRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry(), driver = driver)
                runTest {
                    val stored =
                        repo
                            .recordFetch("book1", ExternalRatingSource.AUDIBLE, 4.2, 100, "us", 1_000L)
                            .shouldBeInstanceOf<AppResult.Success<ExternalRatingSyncPayload>>()
                            .data
                    repo.softDelete(BookExternalRatingId("book1", ExternalRatingSource.AUDIBLE, stored.id))

                    val tombstone = repo.pullSince(userId = null, cursor = 0L, limit = 10).items.single()
                    tombstone.id shouldBe stored.id
                    tombstone.bookId shouldBe ""
                    tombstone.deletedAt shouldNotBe null
                }
            }
        }
    })
