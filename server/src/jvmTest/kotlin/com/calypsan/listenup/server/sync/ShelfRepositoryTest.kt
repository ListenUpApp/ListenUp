@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.ShelfSyncPayload
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
 * Tests for [ShelfRepository] and [ShelfBookRepository] — the **first user-scoped**
 * SQLDelight aggregate. Every test runs against a real migrated database as a SQLDelight
 * [com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase] (`sql`, the repo's view),
 * with rows seeded through the SQLDelight seed helpers (see [withSqlDatabase]).
 *
 * Coverage:
 *  - shelf + junction upsert / soft-delete, and the owner `user_id` stamping;
 *  - `sort_order` semantics: append-in-order, idempotent re-add, reorder, remove;
 *  - the user-scoped pull/digest substrate: user A never observes user B's rows
 *    (the load-bearing new behaviour for every per-user aggregate that follows).
 */
class ShelfRepositoryTest :
    FunSpec({

        fun shelfPayload(
            id: String,
            name: String = "To Read",
            isPrivate: Boolean = false,
        ) = ShelfSyncPayload(
            id = id,
            name = name,
            description = "",
            isPrivate = isPrivate,
            revision = 0L,
            updatedAt = 0L,
            createdAt = 0L,
            deletedAt = null,
        )

        // ── shelves: ownership + persistence ──────────────────────────────────────

        test("listOwnedBy returns only the owner's shelves") {
            withSqlDatabase {
                sql.seedTestUser("userA")
                sql.seedTestUser("userB")
                val repo = ShelfRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())

                runTest {
                    repo.upsert(shelfPayload("shelfA"), userId = "userA")

                    repo.listOwnedBy("userA") shouldHaveSize 1
                    repo.listOwnedBy("userA").first().id shouldBe "shelfA"
                    repo.listOwnedBy("userB").shouldBeEmpty()
                }
            }
        }

        test("a shelf row carries the owner's user_id") {
            withSqlDatabase {
                sql.seedTestUser("userA")
                val repo = ShelfRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())

                runTest {
                    repo.upsert(shelfPayload("shelfA"), userId = "userA")

                    val storedUserId =
                        sql.shelvesQueries
                            .selectById("shelfA")
                            .executeAsOne()
                            .user_id
                    storedUserId shouldBe "userA"
                }
            }
        }

        test("isPrivate round-trips through the 0/1 INTEGER boundary") {
            withSqlDatabase {
                sql.seedTestUser("userA")
                val repo = ShelfRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())

                runTest {
                    repo.upsert(shelfPayload("pub", isPrivate = false), userId = "userA")
                    repo.upsert(shelfPayload("priv", isPrivate = true), userId = "userA")

                    repo.findById("pub")?.isPrivate shouldBe false
                    repo.findById("priv")?.isPrivate shouldBe true
                }
            }
        }

        // ── shelf_books: sort order + idempotency ─────────────────────────────────

        test("addBook appends in sort order and is idempotent") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("userA")
                sql.seedTestBook("book1")
                sql.seedTestBook("book2")
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val shelfRepo = ShelfRepository(db = sql, bus = bus, registry = registry)
                val junctionRepo = ShelfBookRepository(db = sql, bus = bus, registry = registry)

                runTest {
                    shelfRepo.upsert(shelfPayload("shelfA"), userId = "userA")
                    junctionRepo.addBook("shelfA", "book1", "userA")
                    junctionRepo.addBook("shelfA", "book2", "userA")

                    val rows = junctionRepo.listByShelf("shelfA")
                    rows shouldHaveSize 2
                    rows[0].bookId shouldBe "book1"
                    rows[0].sortOrder shouldBe 0
                    rows[1].bookId shouldBe "book2"
                    rows[1].sortOrder shouldBe 1

                    // Idempotent re-add does not duplicate.
                    junctionRepo.addBook("shelfA", "book1", "userA")
                    junctionRepo.listByShelf("shelfA") shouldHaveSize 2
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
                val shelfRepo = ShelfRepository(db = sql, bus = bus, registry = registry)
                val junctionRepo = ShelfBookRepository(db = sql, bus = bus, registry = registry)

                runTest {
                    shelfRepo.upsert(shelfPayload("shelfA"), userId = "userA")
                    junctionRepo.addBook("shelfA", "book1", "userA")

                    // The junction's wire id is opaque (SERVER-SYNC-04) — select by shelf,
                    // not by a hardcoded "shelfId:bookId" string.
                    val storedUserId =
                        sql.shelfBooksQueries
                            .selectByShelf("shelfA")
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
                val shelfRepo = ShelfRepository(db = sql, bus = bus, registry = registry)
                val junctionRepo = ShelfBookRepository(db = sql, bus = bus, registry = registry)

                runTest {
                    shelfRepo.upsert(shelfPayload("shelfA"), userId = "userA")
                    junctionRepo.addBook("shelfA", "book1", "userA")
                    junctionRepo.addBook("shelfA", "book2", "userA")
                    junctionRepo.addBook("shelfA", "book3", "userA")

                    val result = junctionRepo.reorder("shelfA", listOf("book3", "book1", "book2"), "userA")
                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()

                    val rows = junctionRepo.listByShelf("shelfA")
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
                val shelfRepo = ShelfRepository(db = sql, bus = bus, registry = registry)
                val junctionRepo = ShelfBookRepository(db = sql, bus = bus, registry = registry)

                runTest {
                    shelfRepo.upsert(shelfPayload("shelfA"), userId = "userA")
                    junctionRepo.addBook("shelfA", "book1", "userA")
                    junctionRepo.addBook("shelfA", "book2", "userA")

                    val result = junctionRepo.removeBook("shelfA", "book1", "userA")
                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()

                    val rows = junctionRepo.listByShelf("shelfA")
                    rows shouldHaveSize 1
                    rows.first().bookId shouldBe "book2"

                    // A removed book is resurrected at the END of the shelf (next free sort_order).
                    junctionRepo.addBook("shelfA", "book1", "userA")
                    val resurrected = junctionRepo.listByShelf("shelfA")
                    resurrected.map { it.bookId } shouldBe listOf("book2", "book1")
                    resurrected.last().sortOrder shouldBe 2
                }
            }
        }

        test("softDeleteByShelf soft-deletes every junction row of the shelf") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("userA")
                sql.seedTestBook("book1")
                sql.seedTestBook("book2")
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val shelfRepo = ShelfRepository(db = sql, bus = bus, registry = registry)
                val junctionRepo = ShelfBookRepository(db = sql, bus = bus, registry = registry)

                runTest {
                    shelfRepo.upsert(shelfPayload("shelfA"), userId = "userA")
                    junctionRepo.addBook("shelfA", "book1", "userA")
                    junctionRepo.addBook("shelfA", "book2", "userA")

                    val count = junctionRepo.softDeleteByShelf("shelfA", "userA")
                    count shouldBe 2

                    junctionRepo.listByShelf("shelfA").shouldBeEmpty()
                }
            }
        }

        // ── user-scoped pull/digest isolation (the new substrate behaviour) ────────

        test("pullSince for user A never returns user B's shelves") {
            withSqlDatabase {
                sql.seedTestUser("userA")
                sql.seedTestUser("userB")
                val repo = ShelfRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())

                runTest {
                    repo.upsert(shelfPayload("a1"), userId = "userA")
                    repo.upsert(shelfPayload("a2"), userId = "userA")
                    repo.upsert(shelfPayload("b1"), userId = "userB")

                    val aPage = repo.pullSince(userId = "userA", cursor = 0L, limit = 50)
                    aPage.items.map { it.id } shouldContainExactlyInAnyOrder listOf("a1", "a2")

                    val bPage = repo.pullSince(userId = "userB", cursor = 0L, limit = 50)
                    bPage.items.map { it.id } shouldContainExactlyInAnyOrder listOf("b1")
                }
            }
        }

        test("pullSince includes the caller's tombstoned shelves only") {
            withSqlDatabase {
                sql.seedTestUser("userA")
                sql.seedTestUser("userB")
                val repo = ShelfRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())

                runTest {
                    repo.upsert(shelfPayload("a1"), userId = "userA")
                    repo.upsert(shelfPayload("b1"), userId = "userB")
                    repo.softDelete(
                        com.calypsan.listenup.core
                            .ShelfId("a1"),
                        userId = "userA",
                    )

                    val aPage = repo.pullSince(userId = "userA", cursor = 0L, limit = 50)
                    aPage.items.map { it.id } shouldContainExactly listOf("a1")
                    aPage.items.first().deletedAt shouldNotBe null

                    // user B's pull is wholly unaffected by user A's tombstone.
                    val bPage = repo.pullSince(userId = "userB", cursor = 0L, limit = 50)
                    bPage.items.map { it.id } shouldContainExactly listOf("b1")
                }
            }
        }

        test("digest for user A covers only user A's rows") {
            withSqlDatabase {
                sql.seedTestUser("userA")
                sql.seedTestUser("userB")
                val repo = ShelfRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())

                runTest {
                    repo.upsert(shelfPayload("a1"), userId = "userA")
                    repo.upsert(shelfPayload("a2"), userId = "userA")
                    repo.upsert(shelfPayload("b1"), userId = "userB")

                    // A high cursor so every row is in-range; the count must be per-user.
                    val aDigest = repo.digest(userId = "userA", cursor = Long.MAX_VALUE)
                    aDigest.count shouldBe 2

                    val bDigest = repo.digest(userId = "userB", cursor = Long.MAX_VALUE)
                    bDigest.count shouldBe 1

                    // Different row sets ⇒ different hashes.
                    aDigest.hash shouldNotBe bDigest.hash
                }
            }
        }

        test("shelf_books pullSince/digest are user-scoped too") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("userA")
                sql.seedTestUser("userB")
                sql.seedTestBook("book1")
                sql.seedTestBook("book2")
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val shelfRepo = ShelfRepository(db = sql, bus = bus, registry = registry)
                val junctionRepo = ShelfBookRepository(db = sql, bus = bus, registry = registry)

                runTest {
                    shelfRepo.upsert(shelfPayload("shelfA"), userId = "userA")
                    shelfRepo.upsert(shelfPayload("shelfB"), userId = "userB")
                    junctionRepo.addBook("shelfA", "book1", "userA")
                    junctionRepo.addBook("shelfB", "book2", "userB")

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
