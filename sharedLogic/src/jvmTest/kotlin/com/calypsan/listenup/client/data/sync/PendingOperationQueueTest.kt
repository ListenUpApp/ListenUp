package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.PendingOperationV2Entity
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest

class PendingOperationQueueTest :
    FunSpec({

        test("enqueue stores an op with a non-blank clientOpId") {
            runTest {
                val db = createInMemoryTestDatabase()
                val queue =
                    PendingOperationQueue(
                        dao = db.pendingOperationV2Dao(),
                        sender = PendingOperationSender { AppResult.Success(Unit) },
                        nowMillis = { 1_000L },
                    )
                val opId =
                    queue.enqueue(
                        domainName = "tags",
                        entityId = "t1",
                        opType = "upsert",
                        payload = "{}",
                        ownerUserId = "u1",
                    )
                opId.isNotBlank() shouldBe true
                db.pendingOperationV2Dao().get(opId) shouldNotBe null
                db.close()
            }
        }

        test("containsAndAck removes a matching op and returns true") {
            runTest {
                val db = createInMemoryTestDatabase()
                val queue =
                    PendingOperationQueue(
                        dao = db.pendingOperationV2Dao(),
                        sender = PendingOperationSender { AppResult.Success(Unit) },
                        nowMillis = { 1_000L },
                    )
                val opId = queue.enqueue("tags", "t1", "upsert", "{}", "u1")
                db.pendingOperationV2Dao().get(opId) shouldNotBe null
                queue.containsAndAck(opId) shouldBe true
                db.pendingOperationV2Dao().get(opId) shouldBe null
                db.close()
            }
        }

        test("containsAndAck returns false for an unknown clientOpId") {
            runTest {
                val db = createInMemoryTestDatabase()
                val queue =
                    PendingOperationQueue(
                        dao = db.pendingOperationV2Dao(),
                        sender = PendingOperationSender { AppResult.Success(Unit) },
                        nowMillis = { 1_000L },
                    )
                queue.containsAndAck("not-a-real-op-id") shouldBe false
                db.close()
            }
        }

        test("drain dispatches one op per (domain, entityId) group at a time") {
            runTest {
                val db = createInMemoryTestDatabase()
                val sent = mutableListOf<String>()
                val sender =
                    PendingOperationSender { op ->
                        sent += op.clientOpId
                        AppResult.Success(Unit)
                    }
                var clock = 0L
                val queue =
                    PendingOperationQueue(
                        dao = db.pendingOperationV2Dao(),
                        sender = sender,
                        nowMillis = { clock++ },
                    )
                val a = queue.enqueue("tags", "t1", "upsert", "{}", "u1")
                val b = queue.enqueue("tags", "t1", "upsert", "{}", "u1")
                val c = queue.enqueue("tags", "t2", "upsert", "{}", "u1")
                queue.drain()
                // First wave: t1's earliest + t2's earliest.
                sent shouldContainExactly listOf(a, c)
                queue.drain()
                // Second wave: t1's next op (b), now that a is gone.
                sent shouldContainExactly listOf(a, c, b)
                db.close()
            }
        }

        test("retryable failure increments failureCount, op stays in queue") {
            runTest {
                val db = createInMemoryTestDatabase()
                var attempts = 0
                val sender =
                    PendingOperationSender {
                        attempts++
                        AppResult.Failure(TransportError.NetworkUnavailable())
                    }
                val queue =
                    PendingOperationQueue(
                        dao = db.pendingOperationV2Dao(),
                        sender = sender,
                        nowMillis = { 1_000L },
                    )
                val opId = queue.enqueue("tags", "t1", "upsert", "{}", "u1")
                queue.drain()
                val expectedAttempts = 1
                attempts shouldBe expectedAttempts
                val stored = db.pendingOperationV2Dao().get(opId)
                stored shouldNotBe null
                stored?.failureCount shouldBe expectedAttempts
                stored?.lastError shouldBe TransportError.NetworkUnavailable().code
                db.close()
            }
        }

        test("non-retryable failure flags op past MAX_RETRYABLE_ATTEMPTS so it never retries") {
            runTest {
                val db = createInMemoryTestDatabase()
                val sender =
                    PendingOperationSender {
                        AppResult.Failure(SyncError.NotFound(domain = "tags", entityId = "t1"))
                    }
                val queue =
                    PendingOperationQueue(
                        dao = db.pendingOperationV2Dao(),
                        sender = sender,
                        nowMillis = { 1_000L },
                    )
                val opId = queue.enqueue("tags", "t1", "upsert", "{}", "u1")
                queue.drain()
                val stored = db.pendingOperationV2Dao().get(opId)
                stored shouldNotBe null
                val maxRetryable = 5
                ((stored?.failureCount ?: 0) > maxRetryable) shouldBe true
                db.close()
            }
        }

        test("clearForUserChange wipes ops not owned by the new user") {
            runTest {
                val db = createInMemoryTestDatabase()
                val queue =
                    PendingOperationQueue(
                        dao = db.pendingOperationV2Dao(),
                        sender = PendingOperationSender { AppResult.Success(Unit) },
                        nowMillis = { 1_000L },
                    )
                val mine = queue.enqueue("tags", "t1", "upsert", "{}", "u1")
                val theirs = queue.enqueue("tags", "t2", "upsert", "{}", "u2")
                queue.clearForUserChange(currentUserId = "u1")
                db.pendingOperationV2Dao().get(mine) shouldNotBe null
                db.pendingOperationV2Dao().get(theirs) shouldBe null
                db.close()
            }
        }

        test("a sender that throws is flagged terminally failed and the wave continues") {
            runTest {
                val db = createInMemoryTestDatabase()
                val sent = mutableListOf<String>()
                val sender =
                    PendingOperationSender { op ->
                        if (op.entityId == "poison") error("sender blew up")
                        sent += op.clientOpId
                        AppResult.Success(Unit)
                    }
                var clock = 0L
                val queue = PendingOperationQueue(dao = db.pendingOperationV2Dao(), sender = sender, nowMillis = { clock++ })
                val poison = queue.enqueue("tags", "poison", "upsert", "{}", "u1")
                val healthy = queue.enqueue("tags", "healthy", "upsert", "{}", "u1")
                val outcome = queue.drain()
                sent shouldContainExactly listOf(healthy)
                outcome.sent shouldBe 1
                outcome.terminalFailures shouldBe 1
                val stored = db.pendingOperationV2Dao().get(poison)
                stored shouldNotBe null
                ((stored?.failureCount ?: 0) > 5) shouldBe true
                db.close()
            }
        }

        test("drain reports remaining dispatchable ops in DrainOutcome") {
            runTest {
                val db = createInMemoryTestDatabase()
                var clock = 0L
                val queue =
                    PendingOperationQueue(
                        dao = db.pendingOperationV2Dao(),
                        sender = PendingOperationSender { AppResult.Success(Unit) },
                        nowMillis = { clock++ },
                    )
                queue.enqueue("tags", "t1", "upsert", "{}", "u1")
                queue.enqueue("tags", "t1", "upsert", "{}", "u1")
                queue.enqueue("tags", "t2", "upsert", "{}", "u1")
                val first = queue.drain()
                first.sent shouldBe 2
                first.remainingDispatchable shouldBe 1
                val second = queue.drain()
                second.sent shouldBe 1
                second.remainingDispatchable shouldBe 0
                db.close()
            }
        }

        test("enqueue with coalesce=true keeps only the latest queued op for the same (domain, entity, opType)") {
            runTest {
                val db = createInMemoryTestDatabase()
                var clock = 0L
                val queue =
                    PendingOperationQueue(
                        dao = db.pendingOperationV2Dao(),
                        sender = PendingOperationSender { AppResult.Success(Unit) },
                        nowMillis = { clock++ },
                    )
                val stale = queue.enqueue("playback_positions", "b1", "upsert", """{"v":1}""", "u1", coalesce = true)
                val other = queue.enqueue("playback_positions", "b2", "upsert", """{"v":9}""", "u1", coalesce = true)
                val latest = queue.enqueue("playback_positions", "b1", "upsert", """{"v":2}""", "u1", coalesce = true)
                db.pendingOperationV2Dao().get(stale) shouldBe null
                db.pendingOperationV2Dao().get(other) shouldNotBe null
                db.pendingOperationV2Dao().get(latest)?.payload shouldBe """{"v":2}"""
                db.pendingOperationV2Dao().countDispatchable() shouldBe 2
                db.close()
            }
        }

        test("coalescing enqueue preserves terminally-failed rows") {
            runTest {
                val db = createInMemoryTestDatabase()
                db.pendingOperationV2Dao().insert(
                    PendingOperationV2Entity(
                        clientOpId = "terminal-1", domainName = "playback_positions", entityId = "b1",
                        opType = "upsert", payload = "{}", enqueuedAt = 1L, lastAttemptAt = 2L,
                        failureCount = 6, lastError = "SYNC_FAILED", ownerUserId = "u1",
                    ),
                )
                val queue = PendingOperationQueue(dao = db.pendingOperationV2Dao(), sender = PendingOperationSender { AppResult.Success(Unit) }, nowMillis = { 1_000L })
                queue.enqueue("playback_positions", "b1", "upsert", "{}", "u1", coalesce = true)
                db.pendingOperationV2Dao().get("terminal-1") shouldNotBe null
                db.close()
            }
        }
    })
