@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.ReadingOrderSyncPayload
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * Tests for [ReadingOrderRepository] and [ReadingOrderBookRepository] — the
 * near-exact sibling of [ShelfRepository] / [ShelfBookRepository]. Every test runs
 * against a real migrated database as a SQLDelight
 * [com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase] (`sql`, the repo's
 * view), with rows seeded through the SQLDelight seed helpers (see [withSqlDatabase]).
 *
 * Coverage:
 *  - reading order + junction upsert / soft-delete, and the owner `user_id` stamping;
 *  - `attribution` round-trips alongside the other content fields;
 *  - `sort_order` semantics: append-in-order, idempotent re-add, reorder, remove;
 *  - the user-scoped pull/digest substrate: user A never observes user B's rows.
 */
class ReadingOrderRepositoryTest :
    FunSpec({

        fun readingOrderPayload(
            id: String,
            name: String = "Cosmere — Chronological",
            attribution: String = "",
            isPrivate: Boolean = false,
        ) = ReadingOrderSyncPayload(
            id = id,
            name = name,
            description = "",
            attribution = attribution,
            isPrivate = isPrivate,
            revision = 0L,
            updatedAt = 0L,
            createdAt = 0L,
            deletedAt = null,
        )

        // ── reading_orders: ownership + persistence ────────────────────────────────

        test("listOwnedBy returns only the owner's reading orders") {
            withSqlDatabase {
                sql.seedTestUser("userA")
                sql.seedTestUser("userB")
                val repo = ReadingOrderRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())

                runTest {
                    repo.upsert(readingOrderPayload("roA"), userId = "userA")

                    repo.listOwnedBy("userA") shouldHaveSize 1
                    repo.listOwnedBy("userA").first().id shouldBe "roA"
                    repo.listOwnedBy("userB").shouldBeEmpty()
                }
            }
        }

        test("a reading order row carries the owner's user_id") {
            withSqlDatabase {
                sql.seedTestUser("userA")
                val repo = ReadingOrderRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())

                runTest {
                    repo.upsert(readingOrderPayload("roA"), userId = "userA")

                    val storedUserId =
                        sql.readingOrdersQueries
                            .selectById("roA")
                            .executeAsOne()
                            .user_id
                    storedUserId shouldBe "userA"
                }
            }
        }

        test("attribution and isPrivate round-trip") {
            withSqlDatabase {
                sql.seedTestUser("userA")
                val repo = ReadingOrderRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())

                runTest {
                    repo.upsert(
                        readingOrderPayload("pub", attribution = "u/Argent", isPrivate = false),
                        userId = "userA",
                    )
                    repo.upsert(readingOrderPayload("priv", isPrivate = true), userId = "userA")

                    repo.findById("pub")?.attribution shouldBe "u/Argent"
                    repo.findById("pub")?.isPrivate shouldBe false
                    repo.findById("priv")?.isPrivate shouldBe true
                }
            }
        }

        // ── reading_order_books: sort order + idempotency ──────────────────────────

        test("addBook appends in sort order and is idempotent") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("userA")
                sql.seedTestBook("book1")
                sql.seedTestBook("book2")
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val roRepo = ReadingOrderRepository(db = sql, bus = bus, registry = registry)
                val junctionRepo = ReadingOrderBookRepository(db = sql, bus = bus, registry = registry)

                runTest {
                    roRepo.upsert(readingOrderPayload("roA"), userId = "userA")
                    junctionRepo.addBook("roA", "book1", "userA")
                    junctionRepo.addBook("roA", "book2", "userA")

                    val rows = junctionRepo.listByReadingOrder("roA")
                    rows shouldHaveSize 2
                    rows[0].bookId shouldBe "book1"
                    rows[0].sortOrder shouldBe 0
                    rows[1].bookId shouldBe "book2"
                    rows[1].sortOrder shouldBe 1

                    // Idempotent re-add does not duplicate.
                    junctionRepo.addBook("roA", "book1", "userA")
                    junctionRepo.listByReadingOrder("roA") shouldHaveSize 2
                }
            }
        }

        test("a junction row carries the owner's user_id") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("userA")
                sql.seedTestBook("book1")
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val roRepo = ReadingOrderRepository(db = sql, bus = bus, registry = registry)
                val junctionRepo = ReadingOrderBookRepository(db = sql, bus = bus, registry = registry)

                runTest {
                    roRepo.upsert(readingOrderPayload("roA"), userId = "userA")
                    junctionRepo.addBook("roA", "book1", "userA")

                    val storedUserId =
                        sql.readingOrderBooksQueries
                            .selectById("roA:book1")
                            .executeAsOne()
                            .user_id
                    storedUserId shouldBe "userA"
                }
            }
        }

        test("reorder rewrites sort order to match the given ordering") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("userA")
                sql.seedTestBook("book1")
                sql.seedTestBook("book2")
                sql.seedTestBook("book3")
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val roRepo = ReadingOrderRepository(db = sql, bus = bus, registry = registry)
                val junctionRepo = ReadingOrderBookRepository(db = sql, bus = bus, registry = registry)

                runTest {
                    roRepo.upsert(readingOrderPayload("roA"), userId = "userA")
                    junctionRepo.addBook("roA", "book1", "userA")
                    junctionRepo.addBook("roA", "book2", "userA")
                    junctionRepo.addBook("roA", "book3", "userA")

                    val result = junctionRepo.reorder("roA", listOf("book3", "book1", "book2"), "userA")
                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()

                    val rows = junctionRepo.listByReadingOrder("roA")
                    rows.map { it.bookId } shouldBe listOf("book3", "book1", "book2")
                    rows.map { it.sortOrder } shouldBe listOf(0, 1, 2)
                }
            }
        }

        test("removeBook soft-deletes the junction row") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("userA")
                sql.seedTestBook("book1")
                sql.seedTestBook("book2")
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val roRepo = ReadingOrderRepository(db = sql, bus = bus, registry = registry)
                val junctionRepo = ReadingOrderBookRepository(db = sql, bus = bus, registry = registry)

                runTest {
                    roRepo.upsert(readingOrderPayload("roA"), userId = "userA")
                    junctionRepo.addBook("roA", "book1", "userA")
                    junctionRepo.addBook("roA", "book2", "userA")

                    val result = junctionRepo.removeBook("roA", "book1", "userA")
                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()

                    val rows = junctionRepo.listByReadingOrder("roA")
                    rows shouldHaveSize 1
                    rows.first().bookId shouldBe "book2"

                    // A removed book is resurrected at the END of the order (next free sort_order).
                    junctionRepo.addBook("roA", "book1", "userA")
                    val resurrected = junctionRepo.listByReadingOrder("roA")
                    resurrected.map { it.bookId } shouldBe listOf("book2", "book1")
                    resurrected.last().sortOrder shouldBe 2
                }
            }
        }

        test("softDeleteByReadingOrder soft-deletes every junction row of the order") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("userA")
                sql.seedTestBook("book1")
                sql.seedTestBook("book2")
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val roRepo = ReadingOrderRepository(db = sql, bus = bus, registry = registry)
                val junctionRepo = ReadingOrderBookRepository(db = sql, bus = bus, registry = registry)

                runTest {
                    roRepo.upsert(readingOrderPayload("roA"), userId = "userA")
                    junctionRepo.addBook("roA", "book1", "userA")
                    junctionRepo.addBook("roA", "book2", "userA")

                    val count = junctionRepo.softDeleteByReadingOrder("roA", "userA")
                    count shouldBe 2

                    junctionRepo.listByReadingOrder("roA").shouldBeEmpty()
                }
            }
        }

        // ── user-scoped pull/digest isolation ──────────────────────────────────────

        test("pullSince for user A never returns user B's reading orders") {
            withSqlDatabase {
                sql.seedTestUser("userA")
                sql.seedTestUser("userB")
                val repo = ReadingOrderRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())

                runTest {
                    repo.upsert(readingOrderPayload("a1"), userId = "userA")
                    repo.upsert(readingOrderPayload("a2"), userId = "userA")
                    repo.upsert(readingOrderPayload("b1"), userId = "userB")

                    val aPage = repo.pullSince(userId = "userA", cursor = 0L, limit = 50)
                    aPage.items.map { it.id } shouldContainExactlyInAnyOrder listOf("a1", "a2")

                    val bPage = repo.pullSince(userId = "userB", cursor = 0L, limit = 50)
                    bPage.items.map { it.id } shouldContainExactlyInAnyOrder listOf("b1")
                }
            }
        }

        test("pullSince includes the caller's tombstoned reading orders only") {
            withSqlDatabase {
                sql.seedTestUser("userA")
                sql.seedTestUser("userB")
                val repo = ReadingOrderRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())

                runTest {
                    repo.upsert(readingOrderPayload("a1"), userId = "userA")
                    repo.upsert(readingOrderPayload("b1"), userId = "userB")
                    repo.softDelete(
                        com.calypsan.listenup.core
                            .ReadingOrderId("a1"),
                        userId = "userA",
                    )

                    val aPage = repo.pullSince(userId = "userA", cursor = 0L, limit = 50)
                    aPage.items.map { it.id } shouldContainExactly listOf("a1")
                    aPage.items.first().deletedAt shouldNotBe null

                    val bPage = repo.pullSince(userId = "userB", cursor = 0L, limit = 50)
                    bPage.items.map { it.id } shouldContainExactly listOf("b1")
                }
            }
        }

        test("digest for user A covers only user A's rows") {
            withSqlDatabase {
                sql.seedTestUser("userA")
                sql.seedTestUser("userB")
                val repo = ReadingOrderRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())

                runTest {
                    repo.upsert(readingOrderPayload("a1"), userId = "userA")
                    repo.upsert(readingOrderPayload("a2"), userId = "userA")
                    repo.upsert(readingOrderPayload("b1"), userId = "userB")

                    val aDigest = repo.digest(userId = "userA", cursor = Long.MAX_VALUE)
                    aDigest.count shouldBe 2

                    val bDigest = repo.digest(userId = "userB", cursor = Long.MAX_VALUE)
                    bDigest.count shouldBe 1

                    aDigest.hash shouldNotBe bDigest.hash
                }
            }
        }

        test("reading_order_books pullSince/digest are user-scoped too") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("userA")
                sql.seedTestUser("userB")
                sql.seedTestBook("book1")
                sql.seedTestBook("book2")
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val roRepo = ReadingOrderRepository(db = sql, bus = bus, registry = registry)
                val junctionRepo = ReadingOrderBookRepository(db = sql, bus = bus, registry = registry)

                runTest {
                    roRepo.upsert(readingOrderPayload("roA"), userId = "userA")
                    roRepo.upsert(readingOrderPayload("roB"), userId = "userB")
                    junctionRepo.addBook("roA", "book1", "userA")
                    junctionRepo.addBook("roB", "book2", "userB")

                    val aPage = junctionRepo.pullSince(userId = "userA", cursor = 0L, limit = 50)
                    aPage.items.map { it.bookId } shouldContainExactly listOf("book1")

                    val bPage = junctionRepo.pullSince(userId = "userB", cursor = 0L, limit = 50)
                    bPage.items.map { it.bookId } shouldContainExactly listOf("book2")

                    junctionRepo.digest(userId = "userA", cursor = Long.MAX_VALUE).count shouldBe 1
                    junctionRepo.digest(userId = "userB", cursor = Long.MAX_VALUE).count shouldBe 1
                }
            }
        }
    })
