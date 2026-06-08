package com.calypsan.listenup.client.data.local.db

import app.cash.turbine.test
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest

/**
 * Covers the sync-substrate queries on the two shelf DAOs (Shelves — Room v26):
 * [ShelfDao] and [ShelfBookDao].
 *
 * Each test uses an isolated in-memory database and resets via `deleteAll` in
 * [beforeEach] so it starts from a blank slate.
 */
class ShelfDaoTest :
    FunSpec({
        val db = createInMemoryTestDatabase()
        val shelfDao: ShelfDao = db.shelfDao()
        val shelfBookDao: ShelfBookDao = db.shelfBookDao()

        beforeEach {
            shelfDao.deleteAll()
            shelfBookDao.deleteAll()
        }
        afterSpec { db.close() }

        // ── ShelfDao: upsert / getById / observe ──────────────────────────────

        test("upsert then getById returns the live shelf") {
            runTest {
                shelfDao.upsert(shelf("s1", "To Read"))
                val result = shelfDao.getById("s1")
                result shouldNotBe null
                result!!.name shouldBe "To Read"
            }
        }

        test("observeMyShelvesWithBookCount excludes tombstoned shelves") {
            runTest {
                shelfDao.upsert(shelf("s1", "Alpha"))
                shelfDao.upsert(shelf("s2", "Beta", deletedAt = 999L))
                shelfDao.observeMyShelvesWithBookCount().test {
                    awaitItem().map { it.shelf.id } shouldContainExactly listOf("s1")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("softDelete hides a shelf from observeMyShelvesWithBookCount") {
            runTest {
                shelfDao.upsert(shelf("s1", "Alpha"))
                shelfDao.observeMyShelvesWithBookCount().test {
                    awaitItem().map { it.shelf.id } shouldContainExactly listOf("s1")
                    shelfDao.softDelete("s1", deletedAt = 500L, revision = 2L)
                    awaitItem().shouldBeEmpty()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("observeMyShelvesWithBookCount returns the live membership count via JOIN") {
            runTest {
                shelfDao.upsert(shelf("s1", "Alpha"))
                shelfBookDao.upsert(member("s1", "b1", sortOrder = 0))
                shelfBookDao.upsert(member("s1", "b2", sortOrder = 1))
                shelfBookDao.upsert(member("s1", "b3", sortOrder = 2, deletedAt = 10L)) // tombstoned, not counted
                shelfDao.observeMyShelvesWithBookCount().test {
                    val row = awaitItem().single()
                    row.shelf.id shouldBe "s1"
                    row.bookCount shouldBe 2
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("observeMyShelvesWithBookCount orders by updatedAt descending") {
            runTest {
                shelfDao.upsert(shelf("s1", "Older", updatedAt = 100L))
                shelfDao.upsert(shelf("s2", "Newer", updatedAt = 200L))
                shelfDao.observeMyShelvesWithBookCount().test {
                    awaitItem().map { it.shelf.id } shouldContainExactly listOf("s2", "s1")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("bookCountFor returns the true live junction count, not capped to the cover-grid limit") {
            runTest {
                shelfDao.upsert(shelf("s1", "Big Shelf"))
                // 6 live books — more than the LIMIT 4 used by coverHashesFor
                repeat(6) { i -> shelfBookDao.upsert(member("s1", "b$i", sortOrder = i)) }
                // 1 tombstoned book — must NOT be counted
                shelfBookDao.upsert(member("s1", "b_deleted", sortOrder = 99, deletedAt = 10L))
                shelfDao.bookCountFor("s1") shouldBe 6
            }
        }

        test("liveIds returns only non-tombstoned shelf ids") {
            runTest {
                shelfDao.upsert(shelf("s1", "Alpha"))
                shelfDao.upsert(shelf("s2", "Beta", deletedAt = 1L))
                shelfDao.liveIds().toSet() shouldBe setOf("s1")
            }
        }

        // ── ShelfBookDao: junction ────────────────────────────────────────────

        test("observeShelfBooks returns live book ids in sort order") {
            runTest {
                shelfBookDao.upsert(member("s1", "b2", sortOrder = 1))
                shelfBookDao.upsert(member("s1", "b1", sortOrder = 0))
                shelfBookDao.observeShelfBooks("s1").test {
                    awaitItem() shouldContainExactly listOf("b1", "b2")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("softDelete hides a junction row from observeShelfBooks") {
            runTest {
                shelfBookDao.upsert(member("s1", "b1", sortOrder = 0))
                shelfBookDao.observeShelfBooks("s1").test {
                    awaitItem() shouldContainExactly listOf("b1")
                    shelfBookDao.softDelete("s1:b1", deletedAt = 50L, revision = 2L)
                    awaitItem().shouldBeEmpty()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("findById returns the junction row by synthetic id") {
            runTest {
                shelfBookDao.upsert(member("s1", "b1", sortOrder = 0))
                shelfBookDao.findById("s1:b1") shouldNotBe null
            }
        }

        // ── ShelfDao: observeShelvesContainingBookWithBookCount ────────────────

        test("observeShelvesContainingBook returns only shelves holding the book, alphabetical (NOCASE)") {
            runTest {
                shelfDao.upsert(shelf("s1", "Beta"))
                shelfDao.upsert(shelf("s2", "alpha"))
                shelfDao.upsert(shelf("s3", "Gamma"))
                shelfBookDao.upsert(member("s1", "b1", sortOrder = 0))
                shelfBookDao.upsert(member("s2", "b1", sortOrder = 0))
                shelfBookDao.upsert(member("s3", "b2", sortOrder = 0)) // different book
                shelfDao.observeShelvesContainingBookWithBookCount("b1").test {
                    awaitItem().map { it.shelf.id } shouldContainExactly listOf("s2", "s1") // alpha < Beta, case-insensitive
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("observeShelvesContainingBook is empty when the book is on no shelf") {
            runTest {
                shelfDao.upsert(shelf("s1", "Alpha"))
                shelfBookDao.upsert(member("s1", "b1", sortOrder = 0))
                shelfDao.observeShelvesContainingBookWithBookCount("nope").test {
                    awaitItem().shouldBeEmpty()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("observeShelvesContainingBook excludes tombstoned membership and tombstoned shelves") {
            runTest {
                shelfDao.upsert(shelf("s1", "Alpha"))
                shelfDao.upsert(shelf("s2", "Beta", deletedAt = 999L)) // tombstoned shelf
                shelfBookDao.upsert(member("s1", "b1", sortOrder = 0, deletedAt = 10L)) // tombstoned membership
                shelfBookDao.upsert(member("s2", "b1", sortOrder = 0)) // live membership, dead shelf
                shelfDao.observeShelvesContainingBookWithBookCount("b1").test {
                    awaitItem().shouldBeEmpty()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("observeShelvesContainingBook reports the shelf's full live bookCount, not 1") {
            runTest {
                shelfDao.upsert(shelf("s1", "Alpha"))
                shelfBookDao.upsert(member("s1", "b1", sortOrder = 0))
                shelfBookDao.upsert(member("s1", "b2", sortOrder = 1))
                shelfBookDao.upsert(member("s1", "b3", sortOrder = 2, deletedAt = 10L)) // tombstoned, not counted
                shelfDao.observeShelvesContainingBookWithBookCount("b1").test {
                    val row = awaitItem().single()
                    row.shelf.id shouldBe "s1"
                    row.bookCount shouldBe 2
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("observeShelvesContainingBook reacts to a new membership row") {
            runTest {
                shelfDao.upsert(shelf("s1", "Alpha"))
                shelfDao.observeShelvesContainingBookWithBookCount("b1").test {
                    awaitItem().shouldBeEmpty()
                    shelfBookDao.upsert(member("s1", "b1", sortOrder = 0))
                    awaitItem().map { it.shelf.id } shouldContainExactly listOf("s1")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }
    })

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun shelf(
    id: String,
    name: String,
    deletedAt: Long? = null,
    updatedAt: Long = 100L,
) = ShelfEntity(
    id = id,
    name = name,
    description = "",
    isPrivate = false,
    revision = 1L,
    deletedAt = deletedAt,
    updatedAt = updatedAt,
    createdAt = 50L,
)

private fun member(
    shelfId: String,
    bookId: String,
    sortOrder: Int,
    deletedAt: Long? = null,
) = ShelfBookEntity(
    id = "$shelfId:$bookId",
    shelfId = shelfId,
    bookId = bookId,
    sortOrder = sortOrder,
    revision = 1L,
    deletedAt = deletedAt,
    updatedAt = 100L,
    createdAt = 50L,
)
