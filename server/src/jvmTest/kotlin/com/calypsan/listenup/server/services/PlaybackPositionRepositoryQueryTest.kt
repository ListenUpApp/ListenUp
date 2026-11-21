@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.PlaybackPositionId
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/**
 * Tests for the query methods behind the PlaybackProgressService RPC surface:
 * [PlaybackPositionRepository.listForUser] and [PlaybackPositionRepository.findByBookIds].
 *
 * Mutation and sync-substrate tests live in [PlaybackPositionRepositoryTest].
 */
class PlaybackPositionRepositoryQueryTest :
    FunSpec({

        // ---- listForUser ---------------------------------------------------------------

        test("listForUser returns only the requesting user's positions (cross-user isolation)") {
            withSqlDatabase {
                val repo = PlaybackPositionRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    // user-a: 3 positions
                    repo.recordPosition("user-a", "book-1", 1_000L, 1_730_000_000_001L, false, 1.0f, null)
                    repo.recordPosition("user-a", "book-2", 2_000L, 1_730_000_000_002L, false, 1.0f, null)
                    repo.recordPosition("user-a", "book-3", 3_000L, 1_730_000_000_003L, false, 1.0f, null)
                    // user-b: 2 positions
                    repo.recordPosition("user-b", "book-1", 9_000L, 1_730_000_999_000L, false, 1.0f, null)
                    repo.recordPosition("user-b", "book-4", 9_000L, 1_730_000_999_000L, false, 1.0f, null)

                    val result = repo.listForUser(UserId("user-a"), limit = 100)
                    result shouldHaveSize 3
                    // No user-b positions leaked
                    val bookIds = result.map { it.bookId }.toSet()
                    bookIds shouldBe setOf("book-1", "book-2", "book-3")
                }
            }
        }

        test("listForUser excludes tombstoned positions") {
            withSqlDatabase {
                val repo = PlaybackPositionRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.recordPosition("user-a", "book-live", 1_000L, 1_730_000_000_001L, false, 1.0f, null)
                    val tombstoneResult =
                        repo.recordPosition("user-a", "book-deleted", 2_000L, 1_730_000_000_002L, false, 1.0f, null)
                    val tombstoneId = (tombstoneResult as AppResult.Success).data.id
                    repo.softDelete(PlaybackPositionId(tombstoneId), userId = "user-a")

                    val result = repo.listForUser(UserId("user-a"), limit = 100)
                    result shouldHaveSize 1
                    result.first().bookId shouldBe "book-live"
                }
            }
        }

        test("listForUser honors limit") {
            withSqlDatabase {
                val repo = PlaybackPositionRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repeat(5) { i ->
                        repo.recordPosition("user-a", "book-$i", i * 1_000L, 1_730_000_000_000L + i, false, 1.0f, null)
                    }

                    val result = repo.listForUser(UserId("user-a"), limit = 3)
                    result shouldHaveSize 3
                }
            }
        }

        test("listForUser returns empty list when user has no positions") {
            withSqlDatabase {
                val repo = PlaybackPositionRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.listForUser(UserId("user-nobody"), limit = 100).shouldBeEmpty()
                }
            }
        }

        // ---- findByBookIds -------------------------------------------------------------

        test("findByBookIds returns sparse matches — only positions that exist") {
            withSqlDatabase {
                val repo = PlaybackPositionRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    // Seed positions for book-1 and book-3 only; book-2 has no position
                    repo.recordPosition("user-a", "book-1", 1_000L, 1_730_000_000_001L, false, 1.0f, null)
                    repo.recordPosition("user-a", "book-3", 3_000L, 1_730_000_000_003L, false, 1.0f, null)

                    val result =
                        repo.findByBookIds(
                            UserId("user-a"),
                            listOf(BookId("book-1"), BookId("book-2"), BookId("book-3")),
                        )
                    result shouldHaveSize 2
                    result.map { it.bookId }.toSet() shouldBe setOf("book-1", "book-3")
                }
            }
        }

        test("findByBookIds with empty input returns empty result") {
            withSqlDatabase {
                val repo = PlaybackPositionRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.recordPosition("user-a", "book-1", 1_000L, 1_730_000_000_001L, false, 1.0f, null)
                    repo.findByBookIds(UserId("user-a"), emptyList()).shouldBeEmpty()
                }
            }
        }

        test("findByBookIds excludes tombstoned positions") {
            withSqlDatabase {
                val repo = PlaybackPositionRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.recordPosition("user-a", "book-live", 1_000L, 1_730_000_000_001L, false, 1.0f, null)
                    val tombstoneResult =
                        repo.recordPosition("user-a", "book-dead", 2_000L, 1_730_000_000_002L, false, 1.0f, null)
                    val tombstoneId = (tombstoneResult as AppResult.Success).data.id
                    repo.softDelete(PlaybackPositionId(tombstoneId), userId = "user-a")

                    val result =
                        repo.findByBookIds(
                            UserId("user-a"),
                            listOf(BookId("book-live"), BookId("book-dead")),
                        )
                    result shouldHaveSize 1
                    result.first().bookId shouldBe "book-live"
                }
            }
        }

        test("findByBookIds is user-scoped — other user's position for the same book not visible") {
            withSqlDatabase {
                val repo = PlaybackPositionRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    // user-b has progress on book-1; user-a does not
                    repo.recordPosition("user-b", "book-1", 5_000L, 1_730_000_000_001L, false, 1.0f, null)

                    val result = repo.findByBookIds(UserId("user-a"), listOf(BookId("book-1")))
                    result.shouldBeEmpty()
                }
            }
        }
    })
