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
 * fault into a typed [AppResult.Failure]. See its KDoc for the two-way split this pins:
 * an [RpcOutcomeUnknownException] (frame sent, response lost) is honest but NON-retryable, and
 * ANY [CancellationException] — including a `withTimeout` bound firing — re-raises rather than
 * becoming a value (the engine converts its own request timeout to OutcomeUnknown upstream, so a
 * cancellation reaching here is a genuine structured-concurrency cancel, never a swallowable fault).
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

        test("a TimeoutCancellationException re-raises as cancellation — the dead retryable-Timeout arm is gone") {
            runTest {
                // A withTimeout bound firing throws TimeoutCancellationException, a CancellationException
                // subtype. The removed TCE arm used to fold it to a retryable Timeout that licensed a blind
                // re-fire of a possibly-sent frame; now it re-raises like any cancellation instead of being
                // swallowed into a Failure value.
                shouldThrow<CancellationException> {
                    catchingRpcResult<String> {
                        withTimeout(1.milliseconds) { awaitCancellation() }
                    }
                }
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
