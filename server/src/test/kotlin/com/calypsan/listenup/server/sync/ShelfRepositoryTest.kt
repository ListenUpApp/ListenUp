@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.ShelfSyncPayload
import com.calypsan.listenup.server.db.ShelvesTable
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Tests for [ShelfRepository] and [ShelfBookRepository] — the userScoped shelves
 * substrate. All tests use a real in-memory database with FK-satisfied book rows;
 * shelf owner ids are plain text (no FK), but users are seeded for realism.
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

        test("listOwnedBy returns only the owner's shelves") {
            withInMemoryDatabase {
                seedTestUser("userA")
                seedTestUser("userB")
                val repo = ShelfRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())

                runTest {
                    repo.upsert(shelfPayload("shelfA"), userId = "userA")

                    repo.listOwnedBy("userA") shouldHaveSize 1
                    repo.listOwnedBy("userA").first().id shouldBe "shelfA"
                    repo.listOwnedBy("userB").shouldBeEmpty()
                }
            }
        }

        test("a shelf row carries the owner's user_id") {
            withInMemoryDatabase {
                val db = this
                seedTestUser("userA")
                val repo = ShelfRepository(db = db, bus = ChangeBus(), registry = SyncRegistry())

                runTest {
                    repo.upsert(shelfPayload("shelfA"), userId = "userA")

                    val storedUserId =
                        transaction(db) {
                            ShelvesTable
                                .selectAll()
                                .where { ShelvesTable.id eq "shelfA" }
                                .first()[ShelvesTable.userId]
                        }
                    storedUserId shouldBe "userA"
                }
            }
        }

        test("addBook appends in sort order and is idempotent") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("userA")
                seedTestBook("book1")
                seedTestBook("book2")
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val shelfRepo = ShelfRepository(db = db, bus = bus, registry = registry)
                val junctionRepo = ShelfBookRepository(db = db, bus = bus, registry = registry)

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
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("userA")
                seedTestBook("book1")
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val shelfRepo = ShelfRepository(db = db, bus = bus, registry = registry)
                val junctionRepo = ShelfBookRepository(db = db, bus = bus, registry = registry)

                runTest {
                    shelfRepo.upsert(shelfPayload("shelfA"), userId = "userA")
                    junctionRepo.addBook("shelfA", "book1", "userA")

                    val storedUserId =
                        transaction(db) {
                            com.calypsan.listenup.server.db.ShelfBooksTable
                                .selectAll()
                                .where { com.calypsan.listenup.server.db.ShelfBooksTable.id eq "shelfA:book1" }
                                .first()[com.calypsan.listenup.server.db.ShelfBooksTable.userId]
                        }
                    storedUserId shouldBe "userA"
                }
            }
        }

        test("reorder rewrites sort order to match the given ordering") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("userA")
                seedTestBook("book1")
                seedTestBook("book2")
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val shelfRepo = ShelfRepository(db = db, bus = bus, registry = registry)
                val junctionRepo = ShelfBookRepository(db = db, bus = bus, registry = registry)

                runTest {
                    shelfRepo.upsert(shelfPayload("shelfA"), userId = "userA")
                    junctionRepo.addBook("shelfA", "book1", "userA")
                    junctionRepo.addBook("shelfA", "book2", "userA")

                    val result = junctionRepo.reorder("shelfA", listOf("book2", "book1"), "userA")
                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()

                    val rows = junctionRepo.listByShelf("shelfA")
                    rows.map { it.bookId } shouldBe listOf("book2", "book1")
                    rows[0].sortOrder shouldBe 0
                    rows[1].sortOrder shouldBe 1
                }
            }
        }

        test("removeBook soft-deletes the junction row") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("userA")
                seedTestBook("book1")
                seedTestBook("book2")
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val shelfRepo = ShelfRepository(db = db, bus = bus, registry = registry)
                val junctionRepo = ShelfBookRepository(db = db, bus = bus, registry = registry)

                runTest {
                    shelfRepo.upsert(shelfPayload("shelfA"), userId = "userA")
                    junctionRepo.addBook("shelfA", "book1", "userA")
                    junctionRepo.addBook("shelfA", "book2", "userA")

                    val result = junctionRepo.removeBook("shelfA", "book1", "userA")
                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()

                    val rows = junctionRepo.listByShelf("shelfA")
                    rows shouldHaveSize 1
                    rows.first().bookId shouldBe "book2"
                }
            }
        }

        test("softDeleteByShelf soft-deletes every junction row of the shelf") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("userA")
                seedTestBook("book1")
                seedTestBook("book2")
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val shelfRepo = ShelfRepository(db = db, bus = bus, registry = registry)
                val junctionRepo = ShelfBookRepository(db = db, bus = bus, registry = registry)

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
    })
