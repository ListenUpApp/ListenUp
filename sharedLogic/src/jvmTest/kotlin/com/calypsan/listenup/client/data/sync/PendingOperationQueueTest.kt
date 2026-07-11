package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.PendingOperationV2Entity
import com.calypsan.listenup.client.data.sync.domains.OpKind
import com.calypsan.listenup.client.data.sync.domains.OutboxChannel
import com.calypsan.listenup.client.data.sync.domains.OutboxChannels
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer

// Queue is payload-agnostic (payload arrives pre-encoded); any serializer works.
private val upsertOnlyChannel = OutboxChannel("tags", String.serializer(), setOf(OpKind.Upsert))

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
                        channel = upsertOnlyChannel,
                        entityId = "t1",
                        op = OpKind.Upsert,
                        payload = "{}",
                        ownerUserId = "u1",
                    )
                opId.isNotBlank() shouldBe true
                db.pendingOperationV2Dao().get(opId) shouldNotBe null
                db.close()
            }
        }

        test("enqueue rejects an op kind the channel does not declare") {
            runTest {
                val db = createInMemoryTestDatabase()
                val queue =
                    PendingOperationQueue(
                        dao = db.pendingOperationV2Dao(),
                        sender = PendingOperationSender { AppResult.Success(Unit) },
                        nowMillis = { 1_000L },
                    )
                shouldThrow<IllegalStateException> {
                    queue.enqueue(upsertOnlyChannel, "t1", OpKind.Create, "{}", "u1")
                }.message shouldContain "tags"
                db.close()
            }
        }

        test("enqueue stores the channel name and the op's wire string") {
            runTest {
                val db = createInMemoryTestDatabase()
                val queue =
                    PendingOperationQueue(
                        dao = db.pendingOperationV2Dao(),
                        sender = PendingOperationSender { AppResult.Success(Unit) },
                        nowMillis = { 1_000L },
                    )
                val opId = queue.enqueue(upsertOnlyChannel, "t1", OpKind.Upsert, "{}", "u1")
                val row = db.pendingOperationV2Dao().get(opId)!!
                row.domainName shouldBe "tags"
                row.opType shouldBe "upsert"
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
                val opId = queue.enqueue(upsertOnlyChannel, "t1", OpKind.Upsert, "{}", "u1")
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
                val a = queue.enqueue(upsertOnlyChannel, "t1", OpKind.Upsert, "{}", "u1")
                val b = queue.enqueue(upsertOnlyChannel, "t1", OpKind.Upsert, "{}", "u1")
                val c = queue.enqueue(upsertOnlyChannel, "t2", OpKind.Upsert, "{}", "u1")
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
                val opId = queue.enqueue(upsertOnlyChannel, "t1", OpKind.Upsert, "{}", "u1")
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
                val opId = queue.enqueue(upsertOnlyChannel, "t1", OpKind.Upsert, "{}", "u1")
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
                val mine = queue.enqueue(upsertOnlyChannel, "t1", OpKind.Upsert, "{}", "u1")
                val theirs = queue.enqueue(upsertOnlyChannel, "t2", OpKind.Upsert, "{}", "u2")
                queue.clearForUserChange(currentUserId = "u1")
                db.pendingOperationV2Dao().get(mine) shouldNotBe null
                db.pendingOperationV2Dao().get(theirs) shouldBe null
                db.close()
            }
        }

        test("a sender that throws a transient transport fault is retried, not terminally dead-lettered") {
            runTest {
                val db = createInMemoryTestDatabase()
                // The dead-proxy fault a raw RPC push throws when the self-hosted server restarts —
                // kotlinx.rpc surfaces it as IllegalStateException("RpcClient was cancelled"), which
                // ErrorMapper would misclassify non-retryable. The queue must NOT lose the op.
                val sender = PendingOperationSender { error("RpcClient was cancelled") }
                val queue =
                    PendingOperationQueue(
                        dao = db.pendingOperationV2Dao(),
                        sender = sender,
                        nowMillis = { 1_000L },
                    )
                val opId = queue.enqueue(upsertOnlyChannel, "flaky", OpKind.Upsert, "{}", "u1")
                val outcome = queue.drain()
                // Treated as a transient (retryable) failure — one attempt burned, op survives.
                outcome.retryableFailures shouldBe 1
                outcome.terminalFailures shouldBe 0
                val stored = db.pendingOperationV2Dao().get(opId)
                stored?.failureCount shouldBe 1
                // Still dispatchable — it retries once the connection heals (firehose reconnect drops
                // the dead proxy), instead of being permanently dead-lettered on the first blip.
                db.pendingOperationV2Dao().nextDispatchable().map { it.clientOpId } shouldContainExactly listOf(opId)
                db.close()
            }
        }

        test("a sender that throws every wave eventually dead-letters after the bounded retry budget") {
            runTest {
                val db = createInMemoryTestDatabase()
                val sender = PendingOperationSender { error("persistent sender bug") }
                var clock = 0L
                val queue =
                    PendingOperationQueue(
                        dao = db.pendingOperationV2Dao(),
                        sender = sender,
                        nowMillis = { clock++ },
                    )
                val opId = queue.enqueue(upsertOnlyChannel, "buggy", OpKind.Upsert, "{}", "u1")
                // Bounded waste: a genuinely broken sender burns MAX_RETRYABLE_ATTEMPTS then quarantines,
                // the same tradeoff a permanently-corrupt payload already accepts.
                repeat(MAX_RETRYABLE_ATTEMPTS + 1) { queue.drain() }
                val stored = db.pendingOperationV2Dao().get(opId)
                stored shouldNotBe null
                ((stored?.failureCount ?: 0) > MAX_RETRYABLE_ATTEMPTS) shouldBe true
                db.pendingOperationV2Dao().nextDispatchable().map { it.clientOpId } shouldContainExactly emptyList()
                db.close()
            }
        }

        test("a sender that throws is retried as transient and the wave continues past it") {
            runTest {
                val db = createInMemoryTestDatabase()
                val sent = mutableListOf<String>()
                val sender =
                    PendingOperationSender { op ->
                        if (op.entityId == "flaky") error("sender blew up")
                        sent += op.clientOpId
                        AppResult.Success(Unit)
                    }
                var clock = 0L
                val queue = PendingOperationQueue(dao = db.pendingOperationV2Dao(), sender = sender, nowMillis = { clock++ })
                val flaky = queue.enqueue(upsertOnlyChannel, "flaky", OpKind.Upsert, "{}", "u1")
                val healthy = queue.enqueue(upsertOnlyChannel, "healthy", OpKind.Upsert, "{}", "u1")
                val outcome = queue.drain()
                // The throw doesn't abort the wave — the healthy op still sends.
                sent shouldContainExactly listOf(healthy)
                outcome.sent shouldBe 1
                // …and the thrower is retried, not terminally dead-lettered.
                outcome.retryableFailures shouldBe 1
                outcome.terminalFailures shouldBe 0
                db.pendingOperationV2Dao().get(flaky)?.failureCount shouldBe 1
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
                queue.enqueue(upsertOnlyChannel, "t1", OpKind.Upsert, "{}", "u1")
                queue.enqueue(upsertOnlyChannel, "t1", OpKind.Upsert, "{}", "u1")
                queue.enqueue(upsertOnlyChannel, "t2", OpKind.Upsert, "{}", "u1")
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
                val stale =
                    queue.enqueue(OutboxChannels.Positions, "b1", OpKind.Upsert, """{"v":1}""", "u1", coalesce = true)
                val other =
                    queue.enqueue(OutboxChannels.Positions, "b2", OpKind.Upsert, """{"v":9}""", "u1", coalesce = true)
                val latest =
                    queue.enqueue(OutboxChannels.Positions, "b1", OpKind.Upsert, """{"v":2}""", "u1", coalesce = true)
                db.pendingOperationV2Dao().get(stale) shouldBe null
                db.pendingOperationV2Dao().get(other) shouldNotBe null
                db.pendingOperationV2Dao().get(latest)?.payload shouldBe """{"v":2}"""
                db.pendingOperationV2Dao().countDispatchable() shouldBe 2
                db.close()
            }
        }

        test("observeFailedOperations emits terminal ops with their lastError code") {
            runTest {
                val db = createInMemoryTestDatabase()
                val sender =
                    PendingOperationSender { op ->
                        if (op.entityId == "t1") {
                            AppResult.Failure(SyncError.NotFound(domain = "tags", entityId = "t1"))
                        } else {
                            AppResult.Success(Unit)
                        }
                    }
                val queue =
                    PendingOperationQueue(
                        dao = db.pendingOperationV2Dao(),
                        sender = sender,
                        nowMillis = { 1_000L },
                    )
                queue.enqueue(upsertOnlyChannel, "t1", OpKind.Upsert, "{}", "u1")
                queue.drain()
                val healthy = queue.enqueue(upsertOnlyChannel, "t2", OpKind.Upsert, "{}", "u1")
                val failed = queue.observeFailedOperations().first()
                failed.map { it.entityId } shouldContainExactly listOf("t1")
                failed.single().lastError shouldBe SyncError.NotFound(domain = "tags", entityId = "t1").code
                val pending = queue.observePendingOperations().first()
                pending.map { it.clientOpId } shouldContainExactly listOf(healthy)
                db.close()
            }
        }

        test("retryOp makes a terminal op dispatchable again and ticks the enqueue signal") {
            runTest {
                val db = createInMemoryTestDatabase()
                var shouldFail = true
                val sender =
                    PendingOperationSender {
                        if (shouldFail) {
                            AppResult.Failure(SyncError.NotFound(domain = "tags", entityId = "t1"))
                        } else {
                            AppResult.Success(Unit)
                        }
                    }
                val queue =
                    PendingOperationQueue(
                        dao = db.pendingOperationV2Dao(),
                        sender = sender,
                        nowMillis = { 1_000L },
                    )
                val opId = queue.enqueue(upsertOnlyChannel, "t1", OpKind.Upsert, "{}", "u1")
                queue.drain()
                db.pendingOperationV2Dao().nextDispatchable().map { it.clientOpId } shouldBe emptyList()
                val signalBefore = queue.observeEnqueueSignal().value
                queue.retryOp(opId)
                queue.observeEnqueueSignal().value shouldBe signalBefore + 1
                db.pendingOperationV2Dao().nextDispatchable().map { it.clientOpId } shouldContainExactly listOf(opId)
                shouldFail = false
                queue.drain()
                db.pendingOperationV2Dao().get(opId) shouldBe null
                db.close()
            }
        }

        test("dismissOp removes the op from the queue") {
            runTest {
                val db = createInMemoryTestDatabase()
                val queue =
                    PendingOperationQueue(
                        dao = db.pendingOperationV2Dao(),
                        sender = PendingOperationSender { AppResult.Success(Unit) },
                        nowMillis = { 1_000L },
                    )
                val opId = queue.enqueue(upsertOnlyChannel, "t1", OpKind.Upsert, "{}", "u1")
                queue.dismissOp(opId)
                db.pendingOperationV2Dao().get(opId) shouldBe null
                val expectedDepth = 0
                queue.observeQueueDepth().first() shouldBe expectedDepth
                db.close()
            }
        }

        test("coalescing enqueue preserves terminally-failed rows") {
            runTest {
                val db = createInMemoryTestDatabase()
                db.pendingOperationV2Dao().insert(
                    PendingOperationV2Entity(
                        clientOpId = "terminal-1",
                        domainName = "playback_positions",
                        entityId = "b1",
                        opType = "upsert",
                        payload = "{}",
                        enqueuedAt = 1L,
                        lastAttemptAt = 2L,
                        failureCount = 6,
                        lastError = "SYNC_FAILED",
                        ownerUserId = "u1",
                    ),
                )
                val queue =
                    PendingOperationQueue(
                        dao = db.pendingOperationV2Dao(),
                        sender = PendingOperationSender { AppResult.Success(Unit) },
                        nowMillis = { 1_000L },
                    )
                queue.enqueue(OutboxChannels.Positions, "b1", OpKind.Upsert, "{}", "u1", coalesce = true)
                db.pendingOperationV2Dao().get("terminal-1") shouldNotBe null
                db.close()
            }
        }

        test("drain GCs dead letters older than the retention window") {
            runTest {
                val db = createInMemoryTestDatabase()
                val dao = db.pendingOperationV2Dao()
                val now = 100L * 24 * 60 * 60 * 1000 // day 100
                val queue =
                    PendingOperationQueue(
                        dao = dao,
                        sender = PendingOperationSender { AppResult.Success(Unit) },
                        nowMillis = { now },
                    )
                dao.insert(
                    deadLetterFixture(
                        "dead-ancient",
                        failureCount = MAX_RETRYABLE_ATTEMPTS + 1,
                        lastAttemptAt = now - 31L * 24 * 60 * 60 * 1000,
                    ),
                )
                dao.insert(
                    deadLetterFixture(
                        "dead-recent",
                        failureCount = MAX_RETRYABLE_ATTEMPTS + 1,
                        lastAttemptAt = now - 1L * 24 * 60 * 60 * 1000,
                    ),
                )

                queue.drain()

                dao.get("dead-ancient") shouldBe null
                dao.get("dead-recent") shouldNotBe null
                db.close()
            }
        }
    })

private fun deadLetterFixture(
    clientOpId: String,
    failureCount: Int = 0,
    enqueuedAt: Long = 1_000L,
    lastAttemptAt: Long? = null,
) = PendingOperationV2Entity(
    clientOpId = clientOpId,
    domainName = "books",
    entityId = "e-$clientOpId",
    opType = "update",
    payload = "{}",
    enqueuedAt = enqueuedAt,
    lastAttemptAt = lastAttemptAt,
    failureCount = failureCount,
    lastError = null,
    ownerUserId = "u1",
)
