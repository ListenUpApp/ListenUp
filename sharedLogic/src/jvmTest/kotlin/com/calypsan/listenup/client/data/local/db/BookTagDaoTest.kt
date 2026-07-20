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
 * Covers the sync-substrate queries on [BookTagDao] (Room v22).
 *
 * The junction table has no FK constraints — the sync handler maintains
 * referential integrity. Tests here focus purely on DAO invariants:
 * upsert semantics, tombstone application, and reactive observation.
 */
class BookTagDaoTest :
    FunSpec({
        val db = createInMemoryTestDatabase()
        val dao: BookTagDao = db.bookTagDao()

        beforeEach { dao.deleteAll() }
        afterSpec { db.close() }

        // ── upsert + findByKey ────────────────────────────────────────────────

        test("upsert inserts a new junction row and findByKey returns it") {
            runTest {
                dao.upsert(bookTag("b1", "t1", createdAt = 100L, revision = 1L))
                val row = dao.findByKey("b1", "t1")
                row shouldNotBe null
                row!!.bookId shouldBe "b1"
                row.tagId shouldBe "t1"
                row.createdAt shouldBe 100L
                row.revision shouldBe 1L
                row.deletedAt shouldBe null
            }
        }

        test("upsert on an existing row replaces the row (idempotent)") {
            runTest {
                dao.upsert(bookTag("b1", "t1", createdAt = 100L, revision = 1L))
                dao.upsert(bookTag("b1", "t1", createdAt = 100L, revision = 2L, deletedAt = null))
                val row = dao.findByKey("b1", "t1")
                row!!.revision shouldBe 2L
                row.deletedAt shouldBe null
            }
        }

        test("findByKey returns null for an absent key") {
            runTest {
                dao.upsert(bookTag("b1", "t1"))
                dao.findByKey("b2", "t1") shouldBe null
                dao.findByKey("b1", "t2") shouldBe null
            }
        }

        // ── tombstone ─────────────────────────────────────────────────────────

        test("tombstone sets deletedAt and the server-authoritative revision on an existing junction row") {
            runTest {
                dao.upsert(bookTag("b1", "t1", revision = 1L))
                dao.tombstone("b1", "t1", deletedAt = 500L, revision = 2L)
                val row = dao.findByKey("b1", "t1")
                row!!.deletedAt shouldBe 500L
                // Writes the event's own revision (not revision + 1) so a replay is a no-op.
                row.revision shouldBe 2L
            }
        }

        test("replaying a tombstone at the same revision is a no-op (idempotent, no double-increment)") {
            runTest {
                dao.upsert(bookTag("b1", "t1", revision = 1L))
                dao.tombstone("b1", "t1", deletedAt = 500L, revision = 2L)
                // The server re-delivers the same Deleted frame (revision 2) — the row must not drift.
                dao.tombstone("b1", "t1", deletedAt = 500L, revision = 2L)
                val row = dao.findByKey("b1", "t1")
                row!!.revision shouldBe 2L
                row.deletedAt shouldBe 500L
            }
        }

        test("upsert after tombstone can clear the tombstone (re-add semantics)") {
            runTest {
                dao.upsert(bookTag("b1", "t1", revision = 1L))
                dao.tombstone("b1", "t1", deletedAt = 500L, revision = 2L)
                // Server creates a new event clearing the tombstone
                dao.upsert(bookTag("b1", "t1", createdAt = 600L, revision = 3L, deletedAt = null))
                val row = dao.findByKey("b1", "t1")
                row!!.deletedAt shouldBe null
                row.revision shouldBe 3L
            }
        }

        // ── observeForBook ────────────────────────────────────────────────────

        test("observeForBook emits live junction rows for the book") {
            runTest {
                dao.upsert(bookTag("b1", "t1"))
                dao.upsert(bookTag("b1", "t2"))
                dao.upsert(bookTag("b2", "t1")) // different book — must not appear

                dao.observeForBook("b1").test {
                    awaitItem().map { it.tagId }.toSet() shouldBe setOf("t1", "t2")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("observeForBook excludes tombstoned junction rows") {
            runTest {
                dao.upsert(bookTag("b1", "t1"))
                dao.upsert(bookTag("b1", "t2", deletedAt = 999L))

                dao.observeForBook("b1").test {
                    awaitItem().map { it.tagId } shouldContainExactly listOf("t1")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("observeForBook re-emits when a junction row is tombstoned") {
            runTest {
                dao.upsert(bookTag("b1", "t1"))
                dao.observeForBook("b1").test {
                    awaitItem().map { it.tagId } shouldContainExactly listOf("t1")
                    dao.tombstone("b1", "t1", deletedAt = 800L, revision = 2L)
                    awaitItem().shouldBeEmpty()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        // ── observeForTag ─────────────────────────────────────────────────────

        test("observeForTag emits live junction rows for the tag") {
            runTest {
                dao.upsert(bookTag("b1", "t1"))
                dao.upsert(bookTag("b2", "t1"))
                dao.upsert(bookTag("b1", "t2")) // different tag — must not appear

                dao.observeForTag("t1").test {
                    awaitItem().map { it.bookId }.toSet() shouldBe setOf("b1", "b2")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("observeForTag excludes tombstoned junction rows") {
            runTest {
                dao.upsert(bookTag("b1", "t1"))
                dao.upsert(bookTag("b2", "t1", deletedAt = 100L))

                dao.observeForTag("t1").test {
                    awaitItem().map { it.bookId } shouldContainExactly listOf("b1")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        // ── SERVER-SYNC-04: opaque syncId ──────────────────────────────────────

        test("tombstoneBySyncId tombstones only the row with that syncId, by identity — never by pair") {
            runTest {
                dao.upsert(bookTag("b1", "t1", syncId = "opaque-1"))
                dao.upsert(bookTag("b1", "t2", syncId = "opaque-2"))

                dao.tombstoneBySyncId("opaque-1", deletedAt = 999L, revision = 5L)

                val tombstoned = dao.findByKey("b1", "t1")!!
                tombstoned.deletedAt shouldBe 999L
                tombstoned.revision shouldBe 5L
                dao.findByKey("b1", "t2")!!.deletedAt shouldBe null
            }
        }

        test("tombstoneBySyncId is a graceful no-op (0 rows affected) for an unmatched syncId") {
            runTest {
                dao.upsert(bookTag("b1", "t1", syncId = "opaque-1"))

                dao.tombstoneBySyncId("never-seen", deletedAt = 999L, revision = 5L) shouldBe 0
                dao.findByKey("b1", "t1")!!.deletedAt shouldBe null
            }
        }

        test("revisionOfSyncId returns the stored revision by opaque identity, tombstones included") {
            runTest {
                dao.upsert(bookTag("b1", "t1", syncId = "opaque-1", revision = 3L))
                dao.revisionOfSyncId("opaque-1") shouldBe 3L

                dao.tombstoneBySyncId("opaque-1", deletedAt = 1L, revision = 9L)
                dao.revisionOfSyncId("opaque-1") shouldBe 9L

                dao.revisionOfSyncId("never-seen") shouldBe null
            }
        }
    })

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun bookTag(
    bookId: String,
    tagId: String,
    createdAt: Long = 1L,
    revision: Long = 1L,
    deletedAt: Long? = null,
    syncId: String = "$bookId:$tagId",
) = BookTagEntity(
    bookId = bookId,
    tagId = tagId,
    syncId = syncId,
    createdAt = createdAt,
    revision = revision,
    deletedAt = deletedAt,
)
