package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds

/**
 * Unit tests for [catchingRpcResult] — the shared boundary that folds a thrown transport
 * fault into a typed [AppResult.Failure]. See its KDoc for the three-way split this pins:
 * a [kotlinx.coroutines.TimeoutCancellationException] (our own bound) is retryable, an
 * [RpcOutcomeUnknownException] (frame sent, response lost) is honest but NON-retryable, and
 * a genuine caller [CancellationException] re-raises rather than becoming a value.
 */
class RpcCallTest :
    FunSpec({

        test("RpcOutcomeUnknownException maps to a non-retryable TransportError.OutcomeUnknown with propagated debugInfo") {
            runTest {
                val result: AppResult<String> =
                    catchingRpcResult { throw RpcOutcomeUnknownException(IllegalStateException("socket closed")) }

                val error =
                    result
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<TransportError.OutcomeUnknown>()
                error.message shouldBe "The request may not have completed. Check before retrying."
                error.isRetryable shouldBe false
                // debugInfo carries the wrapped CAUSE (the real per-instance diagnostic), not the
                // exception's own fixed-constant message. (The class-name prefix in Throwable.toString()
                // is platform-dependent, so assert on the distinctive cause message substring.)
                error.debugInfo!! shouldContain "socket closed"
            }
        }

        test("a TimeoutCancellationException still maps to the retryable TransportError.Timeout (distinct from OutcomeUnknown)") {
            runTest {
                val result: AppResult<String> =
                    catchingRpcResult {
                        withTimeout(1.milliseconds) { awaitCancellation() }
                    }

                result
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<TransportError.Timeout>()
                    .isRetryable shouldBe true
            }
        }

        test("a CancellationException from the call body is re-thrown, not converted to a Failure") {
            runTest {
                shouldThrow<CancellationException> {
                    catchingRpcResult<String> { throw CancellationException("cancelled mid-call") }
                }
            }
        }
    })
