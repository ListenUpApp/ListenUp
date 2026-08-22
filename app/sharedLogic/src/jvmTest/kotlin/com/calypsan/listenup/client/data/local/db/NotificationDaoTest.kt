package com.calypsan.listenup.client.data.local.db

import app.cash.turbine.test
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/**
 * Covers the sync-substrate queries on [NotificationDao] (Room v7).
 *
 * The inbox is per-device-local sync state: rows arrive from the server's notifications
 * domain, tombstones are soft-deletes via [NotificationEntity.deletedAt], and the read
 * state is an optimistic local stamp guarded to be idempotent against replays.
 */
class NotificationDaoTest :
    FunSpec({
        val db = createInMemoryTestDatabase()
        val dao: NotificationDao = db.notificationDao()

        beforeEach { dao.deleteAll() }
        afterSpec { db.close() }

        // ── observeAll ────────────────────────────────────────────────────────

        test("upsert then observeAll emits the row; tombstoned rows are excluded") {
            runTest {
                dao.upsert(notification("n1", createdAt = 100L))
                dao.upsert(notification("n2", createdAt = 200L, deletedAt = 999L))

                dao.observeAll().test {
                    awaitItem().map { it.id } shouldContainExactly listOf("n1")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("observeAll orders live rows newest-first by createdAt") {
            runTest {
                dao.upsert(notification("older", createdAt = 100L))
                dao.upsert(notification("newer", createdAt = 200L))

                dao.observeAll().test {
                    awaitItem().map { it.id } shouldContainExactly listOf("newer", "older")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        // ── observeUnreadCount ────────────────────────────────────────────────

        test("observeUnreadCount counts live null-readAt rows only") {
            runTest {
                dao.upsert(notification("unread1"))
                dao.upsert(notification("unread2"))
                dao.upsert(notification("read1", readAt = 500L))
                dao.upsert(notification("gone", deletedAt = 999L))

                dao.observeUnreadCount().test {
                    awaitItem() shouldBe 2
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        // ── markRead ──────────────────────────────────────────────────────────

        test("markRead stamps readAt once and never overwrites an earlier stamp") {
            runTest {
                dao.upsert(notification("n1"))
                dao.markRead("n1", readAt = 500L)
                // A replayed mark-read with a later timestamp must not move the stamp.
                dao.markRead("n1", readAt = 900L)

                dao.observeAll().test {
                    awaitItem().single().readAt shouldBe 500L
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        // ── tombstone ─────────────────────────────────────────────────────────

        test("tombstone returns 0 for an unknown id (graceful no-op)") {
            runTest {
                dao.upsert(notification("n1"))

                dao.tombstone("never-seen", deletedAt = 999L, revision = 5L) shouldBe 0
                dao.revisionOf("n1") shouldBe 1L
            }
        }

        test("tombstone sets deletedAt and the server-authoritative revision") {
            runTest {
                dao.upsert(notification("n1", revision = 1L))

                dao.tombstone("n1", deletedAt = 500L, revision = 2L) shouldBe 1
                dao.revisionOf("n1") shouldBe 2L
                dao.observeUnreadCount().test {
                    awaitItem() shouldBe 0
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        // ── digestRows ────────────────────────────────────────────────────────

        test("digestRows returns live rows with revision <= max, tombstoned excluded") {
            runTest {
                dao.upsert(notification("n1", revision = 1L))
                dao.upsert(notification("n2", revision = 3L))
                dao.upsert(notification("beyond", revision = 9L))
                dao.upsert(notification("gone", revision = 2L, deletedAt = 999L))

                dao.digestRows(max = 5L) shouldContainExactlyInAnyOrder
                    listOf(
                        IdRevision("n1", 1L),
                        IdRevision("n2", 3L),
                    )
            }
        }

        // ── deleteAll ─────────────────────────────────────────────────────────

        test("deleteAll empties the table") {
            runTest {
                dao.upsert(notification("n1"))
                dao.upsert(notification("n2", deletedAt = 999L))

                dao.deleteAll()

                dao.observeAll().test {
                    awaitItem() shouldBe emptyList()
                    cancelAndIgnoreRemainingEvents()
                }
                dao.revisionOf("n2") shouldBe null
            }
        }
    })

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun notification(
    id: String,
    createdAt: Long = 1L,
    readAt: Long? = null,
    revision: Long = 1L,
    deletedAt: Long? = null,
) = NotificationEntity(
    id = id,
    type = "registration.pending",
    eventJson = """{"type":"registration.pending","id":"$id"}""",
    createdAt = createdAt,
    updatedAt = createdAt,
    readAt = readAt,
    revision = revision,
    deletedAt = deletedAt,
)
