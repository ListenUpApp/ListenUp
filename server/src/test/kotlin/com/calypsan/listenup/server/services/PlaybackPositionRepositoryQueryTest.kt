@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.PlaybackPositionId
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/**
 * Tests for the 4 query methods added to [PlaybackPositionRepository] for the
 * PlaybackProgressService RPC surface: [PlaybackPositionRepository.listForUser],
 * [PlaybackPositionRepository.findByBookIds],
 * [PlaybackPositionRepository.recentlyListenedForUser],
 * [PlaybackPositionRepository.completedForUser].
 *
 * Mutation and sync-substrate tests live in [PlaybackPositionRepositoryTest].
 */
class PlaybackPositionRepositoryQueryTest :
    FunSpec({

        // ---- listForUser ---------------------------------------------------------------

        test("listForUser returns only the requesting user's positions (cross-user isolation)") {
            withInMemoryDatabase {
                val repo = PlaybackPositionRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
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
            withInMemoryDatabase {
                val repo = PlaybackPositionRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
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
            withInMemoryDatabase {
                val repo = PlaybackPositionRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
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
            withInMemoryDatabase {
                val repo = PlaybackPositionRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.listForUser(UserId("user-nobody"), limit = 100).shouldBeEmpty()
                }
            }
        }

        // ---- findByBookIds -------------------------------------------------------------

        test("findByBookIds returns sparse matches — only positions that exist") {
            withInMemoryDatabase {
                val repo = PlaybackPositionRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
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
            withInMemoryDatabase {
                val repo = PlaybackPositionRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.recordPosition("user-a", "book-1", 1_000L, 1_730_000_000_001L, false, 1.0f, null)
                    repo.findByBookIds(UserId("user-a"), emptyList()).shouldBeEmpty()
                }
            }
        }

        test("findByBookIds excludes tombstoned positions") {
            withInMemoryDatabase {
                val repo = PlaybackPositionRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
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
            withInMemoryDatabase {
                val repo = PlaybackPositionRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    // user-b has progress on book-1; user-a does not
                    repo.recordPosition("user-b", "book-1", 5_000L, 1_730_000_000_001L, false, 1.0f, null)

                    val result = repo.findByBookIds(UserId("user-a"), listOf(BookId("book-1")))
                    result.shouldBeEmpty()
                }
            }
        }

        // ---- recentlyListenedForUser ---------------------------------------------------

        test("recentlyListenedForUser excludes isFinished=true positions") {
            withInMemoryDatabase {
                val repo = PlaybackPositionRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    // 3 in-progress + 2 finished
                    repo.recordPosition("user-a", "book-1", 1_000L, 1_730_000_000_001L, false, 1.0f, null)
                    repo.recordPosition("user-a", "book-2", 2_000L, 1_730_000_000_002L, false, 1.0f, null)
                    repo.recordPosition("user-a", "book-3", 3_000L, 1_730_000_000_003L, false, 1.0f, null)
                    repo.recordPosition("user-a", "book-4", 4_000L, 1_730_000_000_004L, true, 1.0f, null)
                    repo.recordPosition("user-a", "book-5", 5_000L, 1_730_000_000_005L, true, 1.0f, null)

                    val result = repo.recentlyListenedForUser(UserId("user-a"), limit = 100)
                    result shouldHaveSize 3
                    result.all { !it.finished } shouldBe true
                }
            }
        }

        test("recentlyListenedForUser excludes positionMs=0 entries") {
            withInMemoryDatabase {
                val repo = PlaybackPositionRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.recordPosition("user-a", "book-started", 5_000L, 1_730_000_000_001L, false, 1.0f, null)
                    repo.recordPosition("user-a", "book-zero", 0L, 1_730_000_000_002L, false, 1.0f, null)

                    val result = repo.recentlyListenedForUser(UserId("user-a"), limit = 100)
                    result shouldHaveSize 1
                    result.first().bookId shouldBe "book-started"
                }
            }
        }

        test("recentlyListenedForUser orders by lastPlayedAt DESC") {
            withInMemoryDatabase {
                val repo = PlaybackPositionRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    // oldest first in insert order, expect newest first in result
                    repo.recordPosition("user-a", "book-old", 1_000L, 1_730_000_000_001L, false, 1.0f, null)
                    repo.recordPosition("user-a", "book-mid", 2_000L, 1_730_000_000_050L, false, 1.0f, null)
                    repo.recordPosition("user-a", "book-new", 3_000L, 1_730_000_000_100L, false, 1.0f, null)

                    val result = repo.recentlyListenedForUser(UserId("user-a"), limit = 100)
                    result shouldHaveSize 3
                    result[0].bookId shouldBe "book-new"
                    result[1].bookId shouldBe "book-mid"
                    result[2].bookId shouldBe "book-old"
                }
            }
        }

        test("recentlyListenedForUser honors limit") {
            withInMemoryDatabase {
                val repo = PlaybackPositionRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repeat(10) { i ->
                        repo.recordPosition("user-a", "book-$i", 1_000L, 1_730_000_000_000L + i, false, 1.0f, null)
                    }

                    val result = repo.recentlyListenedForUser(UserId("user-a"), limit = 4)
                    result shouldHaveSize 4
                }
            }
        }

        test("recentlyListenedForUser is user-scoped") {
            withInMemoryDatabase {
                val repo = PlaybackPositionRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.recordPosition("user-a", "book-1", 1_000L, 1_730_000_000_001L, false, 1.0f, null)
                    repo.recordPosition("user-b", "book-2", 2_000L, 1_730_000_000_002L, false, 1.0f, null)
                    repo.recordPosition("user-b", "book-3", 3_000L, 1_730_000_000_003L, false, 1.0f, null)

                    val result = repo.recentlyListenedForUser(UserId("user-a"), limit = 100)
                    result shouldHaveSize 1
                    result.first().bookId shouldBe "book-1"
                }
            }
        }

        // ---- completedForUser ----------------------------------------------------------

        test("completedForUser returns only isFinished=true positions") {
            withInMemoryDatabase {
                val repo = PlaybackPositionRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.recordPosition("user-a", "book-done-1", 9_000L, 1_730_000_000_001L, true, 1.0f, null)
                    repo.recordPosition("user-a", "book-done-2", 9_000L, 1_730_000_000_002L, true, 1.0f, null)
                    repo.recordPosition("user-a", "book-in-progress", 3_000L, 1_730_000_000_003L, false, 1.0f, null)

                    val result = repo.completedForUser(UserId("user-a"), limit = 100)
                    result shouldHaveSize 2
                    result.all { it.finished } shouldBe true
                }
            }
        }

        test("completedForUser orders by lastPlayedAt DESC") {
            withInMemoryDatabase {
                val repo = PlaybackPositionRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.recordPosition("user-a", "book-done-old", 9_000L, 1_730_000_000_001L, true, 1.0f, null)
                    repo.recordPosition("user-a", "book-done-mid", 9_000L, 1_730_000_000_050L, true, 1.0f, null)
                    repo.recordPosition("user-a", "book-done-new", 9_000L, 1_730_000_000_100L, true, 1.0f, null)

                    val result = repo.completedForUser(UserId("user-a"), limit = 100)
                    result shouldHaveSize 3
                    result[0].bookId shouldBe "book-done-new"
                    result[1].bookId shouldBe "book-done-mid"
                    result[2].bookId shouldBe "book-done-old"
                }
            }
        }

        test("completedForUser excludes tombstoned positions") {
            withInMemoryDatabase {
                val repo = PlaybackPositionRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.recordPosition("user-a", "book-done-live", 9_000L, 1_730_000_000_001L, true, 1.0f, null)
                    val tombstoneResult =
                        repo.recordPosition("user-a", "book-done-dead", 9_000L, 1_730_000_000_002L, true, 1.0f, null)
                    val tombstoneId = (tombstoneResult as AppResult.Success).data.id
                    repo.softDelete(PlaybackPositionId(tombstoneId), userId = "user-a")

                    val result = repo.completedForUser(UserId("user-a"), limit = 100)
                    result shouldHaveSize 1
                    result.first().bookId shouldBe "book-done-live"
                }
            }
        }

        test("completedForUser is user-scoped") {
            withInMemoryDatabase {
                val repo = PlaybackPositionRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.recordPosition("user-a", "book-done", 9_000L, 1_730_000_000_001L, true, 1.0f, null)
                    repo.recordPosition("user-b", "book-done-b", 9_000L, 1_730_000_000_002L, true, 1.0f, null)

                    val result = repo.completedForUser(UserId("user-a"), limit = 100)
                    result shouldHaveSize 1
                    result.first().bookId shouldBe "book-done"
                }
            }
        }

        test("completedForUser honors limit") {
            withInMemoryDatabase {
                val repo = PlaybackPositionRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repeat(8) { i ->
                        repo.recordPosition("user-a", "book-done-$i", 9_000L, 1_730_000_000_000L + i, true, 1.0f, null)
                    }

                    val result = repo.completedForUser(UserId("user-a"), limit = 5)
                    result shouldHaveSize 5
                }
            }
        }

        test("completedForUser returns empty when user has no finished positions") {
            withInMemoryDatabase {
                val repo = PlaybackPositionRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.recordPosition("user-a", "book-in-progress", 5_000L, 1_730_000_000_001L, false, 1.0f, null)
                    repo.completedForUser(UserId("user-a"), limit = 100).shouldBeEmpty()
                }
            }
        }
    })
