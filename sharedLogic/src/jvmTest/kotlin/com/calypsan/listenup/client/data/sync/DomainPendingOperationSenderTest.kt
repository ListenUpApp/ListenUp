package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.result.AppResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

class DomainPendingOperationSenderTest :
    FunSpec({

        test("routes a known domainName op to the registered sender") {
            runTest {
                val recorded = mutableListOf<PendingOperation>()
                val sender =
                    DomainPendingOperationSender(
                        byDomain =
                            mapOf(
                                "playback_positions" to
                                    PendingOperationSender { op ->
                                        recorded += op
                                        AppResult.Success(Unit)
                                    },
                            ),
                    )
                val op = fakeOp(domainName = "playback_positions")

                val result = sender.send(op)

                result shouldBe AppResult.Success(Unit)
                recorded shouldBe listOf(op)
            }
        }

        test("unknown domainName returns SyncError.SyncFailed carrying the domain name") {
            runTest {
                val sender = DomainPendingOperationSender(byDomain = emptyMap())
                val op = fakeOp(domainName = "no_such_domain")

                val result = sender.send(op)

                val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                val error = failure.error.shouldBeInstanceOf<SyncError.SyncFailed>()
                error.debugInfo?.contains("no_such_domain") shouldBe true
            }
        }

        test("failure from the delegate sender is propagated") {
            runTest {
                val expected = SyncError.PushFailed()
                val sender =
                    DomainPendingOperationSender(
                        byDomain =
                            mapOf(
                                "playback_positions" to
                                    PendingOperationSender {
                                        AppResult.Failure(expected)
                                    },
                            ),
                    )
                val op = fakeOp(domainName = "playback_positions")

                val result = sender.send(op)

                result shouldBe AppResult.Failure(expected)
            }
        }
    })

private fun fakeOp(domainName: String): PendingOperation =
    PendingOperation(
        clientOpId = "op-1",
        domainName = domainName,
        entityId = "entity-1",
        opType = "upsert",
        payload = "{}",
        enqueuedAt = 1_000L,
        failureCount = 0,
        ownerUserId = "user-1",
    )
