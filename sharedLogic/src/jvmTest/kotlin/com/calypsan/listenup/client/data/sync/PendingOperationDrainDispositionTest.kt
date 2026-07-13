package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.sync.domains.OpKind
import com.calypsan.listenup.client.data.sync.domains.OutboxChannel
import com.calypsan.listenup.client.data.sync.domains.OutboxChannels
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer

// A synthetic channel whose name is deliberately NOT one of OutboxChannels.all — several tests below
// assert behaviour for an undeclared domain. Do not reuse a real channel name here.
private val upsertOnlyChannel =
    OutboxChannel("undeclared_test_domain", String.serializer(), setOf(OpKind.Upsert), idempotent = true)

/**
 * Drain-failure disposition rules for [PendingOperationQueue]: how each failure class (unreachable, auth,
 * OutcomeUnknown, server-answered-retryable, InternalError, non-retryable, and sender-thrown faults) maps to
 * park / burn / dead-letter. Split from [PendingOperationQueueTest] to keep each spec under the size limit.
 */
class PendingOperationDrainDispositionTest :
    FunSpec({

        test("a server-answered retryable failure (Server5xx) increments failureCount, op stays in queue") {
            runTest {
                val db = createInMemoryTestDatabase()
                val serverErrorStatus = 503
                var attempts = 0
                val sender =
                    PendingOperationSender {
                        attempts++
                        AppResult.Failure(TransportError.Server5xx(statusCode = serverErrorStatus))
                    }
                val queue =
                    PendingOperationQueue(
                        dao = db.pendingOperationV2Dao(),
                        sender = sender,
                        nowMillis = { 1_000L },
                    )
                val opId = queue.enqueue(upsertOnlyChannel, "t1", OpKind.Upsert, "{}", "u1")
                val outcome = queue.drain()
                val expectedAttempts = 1
                attempts shouldBe expectedAttempts
                val stored = db.pendingOperationV2Dao().get(opId)
                stored shouldNotBe null
                stored?.failureCount shouldBe expectedAttempts
                stored?.lastError shouldBe TransportError.Server5xx(statusCode = serverErrorStatus).code
                outcome.retryableFailures shouldBe 1
                outcome.parkedFailures shouldBe 0
                db.close()
            }
        }

        test("an unreachable failure (NetworkUnavailable) parks the op without burning budget") {
            runTest {
                val db = createInMemoryTestDatabase()
                val queue =
                    PendingOperationQueue(
                        dao = db.pendingOperationV2Dao(),
                        sender = PendingOperationSender { AppResult.Failure(TransportError.NetworkUnavailable()) },
                        nowMillis = { 1_000L },
                    )
                val opId = queue.enqueue(upsertOnlyChannel, "t1", OpKind.Upsert, "{}", "u1")
                val outcome = queue.drain()
                val stored = db.pendingOperationV2Dao().get(opId)
                stored shouldNotBe null
                // No server verdict: failureCount unburned, op still dispatchable, recorded as parked.
                stored?.failureCount shouldBe 0
                stored?.lastError shouldBe TransportError.NetworkUnavailable().code
                db.pendingOperationV2Dao().nextDispatchable().map { it.clientOpId } shouldContainExactly listOf(opId)
                outcome.parkedFailures shouldBe 1
                outcome.retryableFailures shouldBe 0
                outcome.terminalFailures shouldBe 0
                db.close()
            }
        }

        test("an auth failure parks the op without burning budget (channel not authorized, not the op's fault)") {
            runTest {
                val db = createInMemoryTestDatabase()
                val queue =
                    PendingOperationQueue(
                        dao = db.pendingOperationV2Dao(),
                        sender = PendingOperationSender { AppResult.Failure(AuthError.SessionExpired()) },
                        nowMillis = { 1_000L },
                    )
                val opId = queue.enqueue(upsertOnlyChannel, "t1", OpKind.Upsert, "{}", "u1")
                val outcome = queue.drain()
                val stored = db.pendingOperationV2Dao().get(opId)
                stored shouldNotBe null
                // Auth failures are op-independent (the channel isn't authorized right now) and the op
                // never reached the server (401 at the handshake), so it parks: budget unburned, still
                // dispatchable, re-sends once the session recovers — never dead-lettered.
                stored?.failureCount shouldBe 0
                stored?.lastError shouldBe AuthError.SessionExpired().code
                db.pendingOperationV2Dao().nextDispatchable().map { it.clientOpId } shouldContainExactly listOf(opId)
                outcome.parkedFailures shouldBe 1
                outcome.terminalFailures shouldBe 0
                db.close()
            }
        }

        test("a server InternalError burns budget (bounded retries) instead of dead-lettering on the first attempt") {
            runTest {
                val db = createInMemoryTestDatabase()
                val queue =
                    PendingOperationQueue(
                        dao = db.pendingOperationV2Dao(),
                        sender = PendingOperationSender { AppResult.Failure(InternalError()) },
                        nowMillis = { 1_000L },
                    )
                val opId = queue.enqueue(upsertOnlyChannel, "t1", OpKind.Upsert, "{}", "u1")
                val outcome = queue.drain()
                val stored = db.pendingOperationV2Dao().get(opId)
                stored shouldNotBe null
                // A sanitized escaped-exception fault is often transient (DB lock, restart race): spend
                // one attempt, not an immediate dead-letter. A persistently-failing op still quarantines
                // after MAX_RETRYABLE_ATTEMPTS.
                stored?.failureCount shouldBe 1
                db.pendingOperationV2Dao().nextDispatchable().map { it.clientOpId } shouldContainExactly listOf(opId)
                outcome.retryableFailures shouldBe 1
                outcome.terminalFailures shouldBe 0
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

        test("OutcomeUnknown on an idempotent channel is parked (retried without burning budget), not dead-lettered") {
            runTest {
                val db = createInMemoryTestDatabase()
                val queue =
                    PendingOperationQueue(
                        dao = db.pendingOperationV2Dao(),
                        sender = PendingOperationSender { AppResult.Failure(TransportError.OutcomeUnknown()) },
                        nowMillis = { 1_000L },
                    )
                // Positions ("playback_positions") is a declared idempotent channel: re-sending is safe.
                // The server never confirmed a verdict, so this parks — no budget spent.
                val opId = queue.enqueue(OutboxChannels.Positions, "b1", OpKind.Upsert, "{}", "u1")
                val outcome = queue.drain()
                val stored = db.pendingOperationV2Dao().get(opId)
                stored shouldNotBe null
                stored?.failureCount shouldBe 0
                db.pendingOperationV2Dao().nextDispatchable().map { it.clientOpId } shouldContainExactly listOf(opId)
                outcome.parkedFailures shouldBe 1
                outcome.retryableFailures shouldBe 0
                outcome.terminalFailures shouldBe 0
                db.close()
            }
        }

        test("a server-answered retryable failure (Server5xx) every wave still dead-letters after MAX (budget preserved for poison)") {
            runTest {
                val db = createInMemoryTestDatabase()
                val serverErrorStatus = 503
                val queue =
                    PendingOperationQueue(
                        dao = db.pendingOperationV2Dao(),
                        sender = PendingOperationSender { AppResult.Failure(TransportError.Server5xx(statusCode = serverErrorStatus)) },
                        nowMillis = { 1_000L },
                    )
                val opId = queue.enqueue(OutboxChannels.Positions, "b1", OpKind.Upsert, "{}", "u1")
                // MAX increments push failureCount to exactly MAX (still dispatchable); one more crosses it.
                repeat(MAX_RETRYABLE_ATTEMPTS + 1) { queue.drain() }
                val stored = db.pendingOperationV2Dao().get(opId)
                stored shouldNotBe null
                ((stored?.failureCount ?: 0) > MAX_RETRYABLE_ATTEMPTS) shouldBe true
                db.pendingOperationV2Dao().nextDispatchable().map { it.clientOpId } shouldBe emptyList()
                db.close()
            }
        }

        test("OutcomeUnknown on a non-idempotent (undeclared) channel is dead-lettered") {
            runTest {
                val db = createInMemoryTestDatabase()
                val queue =
                    PendingOperationQueue(
                        dao = db.pendingOperationV2Dao(),
                        sender = PendingOperationSender { AppResult.Failure(TransportError.OutcomeUnknown()) },
                        nowMillis = { 1_000L },
                    )
                // upsertOnlyChannel's domainName ("undeclared_test_domain") is not a declared
                // OutboxChannels channel, so OutboxChannels.isIdempotent(...) is false → quarantine.
                val opId = queue.enqueue(upsertOnlyChannel, "t1", OpKind.Upsert, "{}", "u1")
                val outcome = queue.drain()
                val stored = db.pendingOperationV2Dao().get(opId)
                stored shouldNotBe null
                ((stored?.failureCount ?: 0) > MAX_RETRYABLE_ATTEMPTS) shouldBe true
                outcome.terminalFailures shouldBe 1
                db.close()
            }
        }

        test("a non-OutcomeUnknown non-retryable error dead-letters even on an idempotent channel") {
            runTest {
                val db = createInMemoryTestDatabase()
                val notFoundStatus = 404
                val queue =
                    PendingOperationQueue(
                        dao = db.pendingOperationV2Dao(),
                        sender = PendingOperationSender { AppResult.Failure(TransportError.Server4xx(statusCode = notFoundStatus)) },
                        nowMillis = { 1_000L },
                    )
                val opId = queue.enqueue(OutboxChannels.Positions, "b1", OpKind.Upsert, "{}", "u1")
                val outcome = queue.drain()
                val stored = db.pendingOperationV2Dao().get(opId)
                stored shouldNotBe null
                ((stored?.failureCount ?: 0) > MAX_RETRYABLE_ATTEMPTS) shouldBe true
                outcome.terminalFailures shouldBe 1
                db.close()
            }
        }

        test("A1 regression: an unreachable send (NetworkUnavailable) across many waves never dead-letters and delivers when reachable") {
            runTest {
                val db = createInMemoryTestDatabase()
                var reachable = false
                val sender =
                    PendingOperationSender {
                        if (reachable) {
                            AppResult.Success(Unit)
                        } else {
                            AppResult.Failure(TransportError.NetworkUnavailable())
                        }
                    }
                val queue =
                    PendingOperationQueue(
                        dao = db.pendingOperationV2Dao(),
                        sender = sender,
                        nowMillis = { 1_000L },
                    )
                val opId = queue.enqueue(OutboxChannels.Positions, "b1", OpKind.Upsert, "{}", "u1")

                // Drain far more waves than the retry budget while the server is unreachable.
                repeat(MAX_RETRYABLE_ATTEMPTS + 2) { queue.drain() }

                // The server never answered, so budget was never spent: the op is unburned and still
                // dispatchable — NOT silently dead-lettered by an outage.
                val parked = db.pendingOperationV2Dao().get(opId)
                parked shouldNotBe null
                parked?.failureCount shouldBe 0
                db.pendingOperationV2Dao().nextDispatchable().map { it.clientOpId } shouldContainExactly listOf(opId)

                // Server returns — the very next drain delivers and removes the op.
                reachable = true
                queue.drain()
                db.pendingOperationV2Dao().get(opId) shouldBe null
                db.close()
            }
        }

        test("A1 regression: a connect Timeout is treated as unreachable and never burns budget") {
            runTest {
                val db = createInMemoryTestDatabase()
                val queue =
                    PendingOperationQueue(
                        dao = db.pendingOperationV2Dao(),
                        sender = PendingOperationSender { AppResult.Failure(TransportError.Timeout()) },
                        nowMillis = { 1_000L },
                    )
                val opId = queue.enqueue(OutboxChannels.Positions, "b1", OpKind.Upsert, "{}", "u1")
                repeat(MAX_RETRYABLE_ATTEMPTS + 2) { queue.drain() }
                val parked = db.pendingOperationV2Dao().get(opId)
                parked shouldNotBe null
                parked?.failureCount shouldBe 0
                db.pendingOperationV2Dao().nextDispatchable().map { it.clientOpId } shouldContainExactly listOf(opId)
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
    })
