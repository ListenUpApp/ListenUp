package com.calypsan.listenup.client.data.local.db

import app.cash.turbine.test
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/**
 * Tests for [ListeningEventDao.observeWithinWindow].
 *
 * Verifies user-scoping, tombstone exclusion, window boundary semantics,
 * and reactive re-emission on insert.
 */
class ListeningEventDaoTest :
    FunSpec({

        test("observeWithinWindow returns only matching user events inside the window") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val dao = db.listeningEventDao()

                    // u1: events at endedAt = 100, 500, 1000
                    dao.upsert(makeEvent("e1", userId = "u1", startedAt = 90, endedAt = 100))
                    dao.upsert(makeEvent("e2", userId = "u1", startedAt = 490, endedAt = 500))
                    dao.upsert(makeEvent("e3", userId = "u1", startedAt = 990, endedAt = 1000))
                    // u2: event at endedAt = 500 (same window, different user)
                    dao.upsert(makeEvent("e4", userId = "u2", startedAt = 490, endedAt = 500))

                    // Window [200, 800) for u1 should include only e2 (endedAt = 500)
                    dao.observeWithinWindow(userId = "u1", startMs = 200, endMs = 800).test {
                        val items = awaitItem()
                        items.map { it.id } shouldContainExactly listOf("e2")
                        cancelAndIgnoreRemainingEvents()
                    }
                }
            } finally {
                db.close()
            }
        }

        test("observeWithinWindow excludes tombstoned events") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val dao = db.listeningEventDao()

                    dao.upsert(makeEvent("live", userId = "u1", startedAt = 490, endedAt = 500))
                    dao.upsert(
                        makeEvent(
                            "tomb",
                            userId = "u1",
                            startedAt = 491,
                            endedAt = 501,
                            deletedAt = 999L,
                        ),
                    )

                    dao.observeWithinWindow(userId = "u1", startMs = 0, endMs = 10_000).test {
                        val items = awaitItem()
                        items.map { it.id } shouldContainExactly listOf("live")
                        cancelAndIgnoreRemainingEvents()
                    }
                }
            } finally {
                db.close()
            }
        }

        test("observeWithinWindow re-emits when a new event is inserted into the window") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val dao = db.listeningEventDao()

                    dao.upsert(makeEvent("e1", userId = "u1", startedAt = 490, endedAt = 500))

                    dao.observeWithinWindow(userId = "u1", startMs = 0, endMs = 10_000).test {
                        awaitItem().map { it.id } shouldBe listOf("e1")

                        // Insert a second event inside the window
                        dao.upsert(makeEvent("e2", userId = "u1", startedAt = 600, endedAt = 700))

                        val updated = awaitItem()
                        updated.map { it.id }.sorted() shouldBe listOf("e1", "e2")
                        cancelAndIgnoreRemainingEvents()
                    }
                }
            } finally {
                db.close()
            }
        }

        // ====================== observeByBookForUser ======================

        test("observeByBookForUser returns only the target user's events for the target book") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val dao = db.listeningEventDao()

                    // u1 / bookA — the expected results
                    dao.upsert(makeEventForBook("e1", userId = "u1", bookId = "bookA", startedAt = 100, endedAt = 200))
                    dao.upsert(makeEventForBook("e2", userId = "u1", bookId = "bookA", startedAt = 300, endedAt = 400))
                    // u2 / bookA — different user, must be excluded
                    dao.upsert(makeEventForBook("e3", userId = "u2", bookId = "bookA", startedAt = 100, endedAt = 200))
                    // u1 / bookB — different book, must be excluded
                    dao.upsert(makeEventForBook("e4", userId = "u1", bookId = "bookB", startedAt = 100, endedAt = 200))

                    dao.observeByBookForUser(userId = "u1", bookId = "bookA").test {
                        val items = awaitItem()
                        // Newest first: e2 (endedAt=400) before e1 (endedAt=200)
                        items.map { it.id } shouldContainExactly listOf("e2", "e1")
                        cancelAndIgnoreRemainingEvents()
                    }
                }
            } finally {
                db.close()
            }
        }

        test("observeByBookForUser excludes tombstoned events for that (user, book)") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val dao = db.listeningEventDao()

                    dao.upsert(makeEventForBook("live", userId = "u1", bookId = "bookA", startedAt = 100, endedAt = 200))
                    dao.upsert(
                        makeEventForBook(
                            "tomb",
                            userId = "u1",
                            bookId = "bookA",
                            startedAt = 300,
                            endedAt = 400,
                            deletedAt = 999L,
                        ),
                    )

                    dao.observeByBookForUser(userId = "u1", bookId = "bookA").test {
                        val items = awaitItem()
                        items.map { it.id } shouldContainExactly listOf("live")
                        cancelAndIgnoreRemainingEvents()
                    }
                }
            } finally {
                db.close()
            }
        }

        test("observeByBookForUser re-emits when a new event is inserted for that (user, book)") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val dao = db.listeningEventDao()

                    dao.upsert(makeEventForBook("e1", userId = "u1", bookId = "bookA", startedAt = 100, endedAt = 200))

                    dao.observeByBookForUser(userId = "u1", bookId = "bookA").test {
                        awaitItem().map { it.id } shouldContainExactly listOf("e1")

                        dao.upsert(makeEventForBook("e2", userId = "u1", bookId = "bookA", startedAt = 300, endedAt = 400))

                        val updated = awaitItem()
                        // e2 (endedAt=400) should be first
                        updated.map { it.id } shouldContainExactly listOf("e2", "e1")
                        cancelAndIgnoreRemainingEvents()
                    }
                }
            } finally {
                db.close()
            }
        }

        // ── Blank-userId repair ───────────────────────────────────────────────────────────────

        test("reassignBlankUserId re-stamps blank rows and leaves non-blank rows untouched") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val dao = db.listeningEventDao()

                    // One poisoned row (blank userId, written during a startup catch-up race)
                    dao.upsert(makeEvent("poisoned", userId = "", startedAt = 100, endedAt = 200))
                    // One normal row that must remain untouched
                    dao.upsert(makeEvent("ok", userId = "user-123", startedAt = 300, endedAt = 400))

                    val repaired = dao.reassignBlankUserId("user-123")
                    repaired shouldBe 1
                    dao.getById("poisoned")!!.userId shouldBe "user-123"
                    dao.getById("ok")!!.userId shouldBe "user-123"

                    // Idempotent — a second call is a no-op
                    val repairedAgain = dao.reassignBlankUserId("user-123")
                    repairedAgain shouldBe 0
                }
            } finally {
                db.close()
            }
        }
    })

private fun makeEvent(
    id: String,
    userId: String,
    startedAt: Long,
    endedAt: Long,
    deletedAt: Long? = null,
): ListeningEventEntity =
    ListeningEventEntity(
        id = id,
        userId = userId,
        bookId = "book-$id",
        startPositionMs = 0L,
        endPositionMs = endedAt - startedAt,
        startedAt = startedAt,
        endedAt = endedAt,
        playbackSpeed = 1.0f,
        tz = "UTC",
        deviceLabel = null,
        revision = 0,
        deletedAt = deletedAt,
    )

private fun makeEventForBook(
    id: String,
    userId: String,
    bookId: String,
    startedAt: Long,
    endedAt: Long,
    deletedAt: Long? = null,
): ListeningEventEntity =
    ListeningEventEntity(
        id = id,
        userId = userId,
        bookId = bookId,
        startPositionMs = 0L,
        endPositionMs = endedAt - startedAt,
        startedAt = startedAt,
        endedAt = endedAt,
        playbackSpeed = 1.0f,
        tz = "UTC",
        deviceLabel = null,
        revision = 0,
        deletedAt = deletedAt,
    )
