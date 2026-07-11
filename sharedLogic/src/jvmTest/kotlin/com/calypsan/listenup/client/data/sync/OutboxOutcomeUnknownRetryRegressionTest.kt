package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.PlaybackService
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.RecordPositionRequest
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.RpcOutcomeUnknownException
import com.calypsan.listenup.client.data.remote.forTestScripted
import com.calypsan.listenup.client.data.sync.domains.OpKind
import com.calypsan.listenup.client.data.sync.domains.OutboxChannels
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/**
 * The regression this wave exists to close: an idempotent outbox op whose RPC frame is **sent** but
 * whose response is **lost** (a `TransportError.OutcomeUnknown`, surfaced as a thrown
 * [RpcOutcomeUnknownException]) must be **retried**, not terminally dead-lettered on the first
 * attempt.
 *
 * Before this wave, the four idempotent outbox bindings (positions, listening-events, profile,
 * preferences) dispatched a **raw** service proxy that threw on a lost response. A raw throw escapes
 * [OutboxOpSender.send], lands in [PendingOperationQueue.drain]'s `catch (e: Exception)` clause, and
 * flags the op terminal (`failureCount = MAX_RETRYABLE_ATTEMPTS + 1`) on the very first attempt —
 * silent data loss, even though the channel is declared idempotent.
 *
 * Routing the same call through [RpcChannel.call] folds the thrown [RpcOutcomeUnknownException] to
 * `AppResult.Failure(TransportError.OutcomeUnknown)`, which the drain's typed-failure path routes to
 * [OutboxChannels.isIdempotent] → retried (not lost). This test drives that exact throw through the
 * real binding via [RpcChannel.forTestScripted] and asserts the op survives.
 */
class OutboxOutcomeUnknownRetryRegressionTest :
    FunSpec({
        test("an idempotent outbox op whose send loses its response (OutcomeUnknown) is retried, not dead-lettered") {
            runTest {
                val db = createInMemoryTestDatabase()

                // The channel throws exactly the fault kotlinx.rpc raises when a frame is delivered but
                // the response never arrives, BEFORE reaching the service — so the service is never
                // actually invoked (a bare mock is enough).
                val scriptedChannel =
                    RpcChannel.forTestScripted(
                        mock<PlaybackService>(),
                        faults = listOf(RpcOutcomeUnknownException(IllegalStateException("frame sent, response lost"))),
                    )

                // The real production binding shape: Positions is a declared idempotent channel whose
                // sender routes through channel.call — the seam that folds the throw to a typed Failure.
                val sender =
                    DomainPendingOperationSender(
                        mapOf(
                            OutboxChannels.Positions.name to
                                OutboxOpSender(OutboxChannels.Positions) { _, request ->
                                    scriptedChannel.call { it.recordPosition(request) }
                                },
                        ),
                    )

                val queue =
                    PendingOperationQueue(
                        dao = db.pendingOperationV2Dao(),
                        sender = sender,
                        nowMillis = { 1_000L },
                    )

                val payload =
                    contractJson.encodeToString(
                        RecordPositionRequest.serializer(),
                        RecordPositionRequest(
                            bookId = "b1",
                            positionMs = 1_000L,
                            lastPlayedAt = 42L,
                            finished = false,
                            playbackSpeed = 1.0f,
                            currentChapterId = null,
                        ),
                    )
                val opId = queue.enqueue(OutboxChannels.Positions, "b1", OpKind.Upsert, payload, "u1")

                val outcome = queue.drain()

                // Retried, NOT dead-lettered: failureCount incremented by one (well within budget),
                // the op is still dispatchable, and the drain classifies it as a retryable failure.
                val stored = db.pendingOperationV2Dao().get(opId)
                stored.shouldNotBeNull()
                stored.failureCount shouldBe 1
                (stored.failureCount > MAX_RETRYABLE_ATTEMPTS) shouldBe false
                db.pendingOperationV2Dao().nextDispatchable().map { it.clientOpId } shouldContainExactly listOf(opId)
                outcome.retryableFailures shouldBe 1
                outcome.terminalFailures shouldBe 0

                db.close()
            }
        }
    })
