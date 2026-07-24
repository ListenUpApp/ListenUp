package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.core.ServerUrl
import dev.mokkery.answering.calls
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.mock
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.websocket.WebSocketException
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent

/**
 * Unit tests for [RpcProxyCache.call] — the bounded, single-flight, self-healing recovery engine.
 *
 * The proxy is a scripted [FakeProxy]: [connect] is a fake counting lambda that pops the next
 * scripted behavior, so each test controls exactly how the proxy fails and how the reconnect
 * succeeds. [ApiClientFactory.getClient] returns a real MockEngine-backed [HttpClient] so the
 * cache's `.config { installKrpc { } }` derivation runs for real; [ServerConfig.getActiveUrl]
 * returns a fixed URL. No real WebSocket is opened — recovery is exercised structurally.
 */
class RpcProxyCacheCallTest :
    FunSpec({

        /** A scripted proxy whose single method replays [behavior] on every call. */
        class FakeProxy(
            private val behavior: suspend () -> String,
        ) {
            suspend fun work(): String = behavior()
        }

        /** Counting [RpcAuthRecovery] so tests can assert refresh-and-rebuild ran exactly once. */
        class CountingAuthRecovery : RpcAuthRecovery {
            var count = 0
                private set

            override suspend fun refreshAndRebuild(): AuthRecoveryOutcome {
                count++
                return AuthRecoveryOutcome.Refreshed
            }
        }

        // A fresh MockEngine client per getClient() so a dropped `.config { }` child can never
        // interfere with the next lease — the test isolates the recovery engine, not client reuse.
        fun mockFactory(): ApiClientFactory =
            mock {
                everySuspend { getClient() } calls { HttpClient(MockEngine { respond("") }) { install(WebSockets) } }
            }

        fun mockServerConfig(): ServerConfig = mock { everySuspend { getActiveUrl() } returns ServerUrl("http://localhost") }

        /**
         * Build a cache whose [connect] pops the next scripted behavior and counts invocations.
         * The returned pair is (cache, connectCount-getter).
         */
        fun scriptedCache(
            script: ArrayDeque<suspend () -> String>,
            authRecovery: RpcAuthRecovery = RpcAuthRecovery.None,
            preDeliveryRetryBackoff: Duration = 300.milliseconds,
        ): Pair<RpcProxyCache<FakeProxy>, () -> Int> {
            var connectCount = 0
            val cache =
                RpcProxyCache(mockFactory(), mockServerConfig(), authRecovery, preDeliveryRetryBackoff) { _, _ ->
                    connectCount++
                    FakeProxy(script.removeFirst())
                }
            return cache to { connectCount }
        }

        test("a from-below CancellationException (post-delivery) is surfaced outcome-unknown, NOT retried") {
            // kotlinx.rpc throws a bare CancellationException("Client cancelled") from below when it
            // closes a PENDING (already-sent) request channel — the frame was delivered and may have
            // committed. Retrying would double-apply the mutation. The engine must surface, not retry.
            runTest {
                val (cache, connects) =
                    scriptedCache(
                        ArrayDeque(
                            listOf(
                                { throw CancellationException("Client cancelled") },
                                { "must-not-run" },
                            ),
                        ),
                    )

                val result: AppResult<String> = catchingRpcResult { cache.call { AppResult.Success(it.work()) } }

                result
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<TransportError.OutcomeUnknown>()
                connects() shouldBe 1 // NO retry — the second scripted behavior is never reached
            }
        }

        test("a pre-delivery transport failure waits the backoff before its single retry") {
            val backoff = 300.milliseconds
            runTest {
                val (cache, connects) =
                    scriptedCache(
                        ArrayDeque(
                            listOf(
                                { throw IllegalStateException("RpcClient was cancelled") },
                                { "healed" },
                            ),
                        ),
                        preDeliveryRetryBackoff = backoff,
                    )

                val call = async { cache.call { it.work() } }
                runCurrent() // first attempt leases + fails pre-delivery; the backoff delay is scheduled
                connects() shouldBe 1 // the retry has NOT reconnected yet — it's settling out the backoff

                advanceTimeBy(backoff.inWholeMilliseconds + 1)
                runCurrent()
                call.await() shouldBe "healed"
                connects() shouldBe 2 // the single retry reconnected only after the settle
            }
        }

        test("a dead-RpcClient IllegalStateException reconnects and returns the retry result") {
            runTest {
                val (cache, connects) =
                    scriptedCache(
                        ArrayDeque(
                            listOf(
                                { throw IllegalStateException("RpcClient was cancelled") },
                                { "healed" },
                            ),
                        ),
                    )

                cache.call { it.work() } shouldBe "healed"
                connects() shouldBe 2 // 1 original (dead) + 1 reconnect
            }
        }

        test("a genuinely cancelled caller context re-raises without invalidating") {
            runTest {
                val (cache, connects) = scriptedCache(ArrayDeque(listOf({ awaitCancellation() })))

                // UNDISPATCHED so the body runs to awaitCancellation (past connect) before we cancel.
                val job = launch(start = CoroutineStart.UNDISPATCHED) { cache.call { it.work() } }
                job.cancel()
                job.join()

                // Re-raised (not swallowed into a retry): the job ends cancelled, not completed.
                job.isCancelled shouldBe true
                // And the proxy was never invalidated: a follow-up call leases the SAME cached proxy
                // (identity block — never touches the still-parked `work()`), connect stays 1.
                cache.call { it }
                connects() shouldBe 1
            }
        }

        test("a business AppResult.Failure returned by the block passes through — no invalidate, no retry") {
            runTest {
                val (cache, connects) = scriptedCache(ArrayDeque(listOf({ "healthy" }, { "unused" })))

                val result: AppResult<String> =
                    cache.call { AppResult.Failure(ValidationError(message = "duplicate")) }

                result.shouldBeInstanceOf<AppResult.Failure>()
                // A follow-up succeeds on the SAME cached proxy: the Failure never triggered a reconnect.
                cache.call { it.work() } shouldBe "healthy"
                connects() shouldBe 1
            }
        }

        test("a hanging block trips the timeout, invalidates, and never auto-retries") {
            runTest {
                var calls = 0
                val (cache, connects) =
                    scriptedCache(
                        ArrayDeque(
                            listOf(
                                {
                                    calls++
                                    awaitCancellation()
                                },
                                { "reconnected" },
                            ),
                        ),
                    )

                var timedOut = false
                try {
                    cache.call(timeout = 50.milliseconds) { it.work() }
                } catch (_: RpcOutcomeUnknownException) {
                    // A post-send bound trip surfaces as the non-retryable RpcOutcomeUnknownException
                    // (NOT the raw TimeoutCancellationException), so a blind retry can't double-apply.
                    timedOut = true
                }

                timedOut shouldBe true
                calls shouldBe 1 // called exactly once — no auto-retry (no double-mutation)
                // The proxy WAS invalidated for the next attempt: a follow-up reconnects.
                cache.call { it.work() } shouldBe "reconnected"
                connects() shouldBe 2
            }
        }

        test("a first-attempt post-send timeout folds to a non-retryable OutcomeUnknown (symmetric with the retry leg)") {
            runTest {
                var calls = 0
                val (cache, connects) =
                    scriptedCache(
                        ArrayDeque(
                            listOf(
                                {
                                    calls++
                                    awaitCancellation()
                                },
                                { "must-not-run" },
                            ),
                        ),
                    )

                val result: AppResult<String> =
                    catchingRpcResult { cache.call(timeout = 50.milliseconds) { AppResult.Success(it.work()) } }

                val error =
                    result
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<TransportError.OutcomeUnknown>()
                error.isRetryable shouldBe false // a possibly-committed mutation must NOT be blindly re-fired
                calls shouldBe 1 // called exactly once — no auto-retry (the second behavior never runs)
                connects() shouldBe 1 // one lease only: the timeout branch invalidates but never re-leases
            }
        }

        test("a herd failing on the same generation converges on a single reconnect (single-flight)") {
            runTest {
                val barrier = CompletableDeferred<Unit>()
                val (cache, connects) =
                    scriptedCache(
                        ArrayDeque(
                            listOf(
                                {
                                    barrier.await()
                                    throw IllegalStateException("RpcClient was cancelled")
                                },
                                { "healed" },
                            ),
                        ),
                    )

                val herd = List(5) { async { cache.call { it.work() } } }
                barrier.complete(Unit) // release all five parked calls at once
                val results = herd.awaitAll()

                results shouldBe List(5) { "healed" }
                connects() shouldBe 2 // 1 original + exactly 1 reconnect — not 6
            }
        }

        test("a handshake 401 refreshes once, retries once, and a second 401 surfaces typed") {
            runTest {
                val recovery = CountingAuthRecovery()
                val (cache, connects) =
                    scriptedCache(
                        ArrayDeque(
                            listOf(
                                { throw WebSocketException("expected status code 101 but was 401") },
                                { throw WebSocketException("expected status code 101 but was 401") },
                            ),
                        ),
                        authRecovery = recovery,
                    )

                val result: AppResult<String> = catchingRpcResult { cache.call { AppResult.Success(it.work()) } }

                result
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<AuthError.SessionExpired>()
                recovery.count shouldBe 1 // refreshed exactly once (not again on the retry)
                connects() shouldBe 2
            }
        }

        test("a handshake 401 whose refresh is server-confirmed invalid surfaces SessionExpired without a doomed retry") {
            runTest {
                // Refresh is server-confirmed dead (tokens cleared) → retrying the handshake would just 401 again.
                val failingRecovery =
                    object : RpcAuthRecovery {
                        var count = 0

                        override suspend fun refreshAndRebuild(): AuthRecoveryOutcome {
                            count++
                            return AuthRecoveryOutcome.SessionInvalid
                        }
                    }
                val (cache, connects) =
                    scriptedCache(
                        ArrayDeque(
                            listOf(
                                { throw WebSocketException("expected status code 101 but was 401") },
                                { "must-not-run" },
                            ),
                        ),
                        authRecovery = failingRecovery,
                    )

                val result: AppResult<String> = catchingRpcResult { cache.call { AppResult.Success(it.work()) } }

                result
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<AuthError.SessionExpired>()
                failingRecovery.count shouldBe 1
                connects() shouldBe 1 // no retry — the second behavior is never reached
            }
        }

        test("a handshake 401 whose refresh fails TRANSIENTLY keeps the session and surfaces a retryable error (C5)") {
            runTest {
                // A network blip during the 401-heal is NOT session death — the session must survive.
                val transientRecovery =
                    object : RpcAuthRecovery {
                        var count = 0

                        override suspend fun refreshAndRebuild(): AuthRecoveryOutcome {
                            count++
                            return AuthRecoveryOutcome.Transient
                        }
                    }
                val (cache, connects) =
                    scriptedCache(
                        ArrayDeque(
                            listOf(
                                { throw WebSocketException("expected status code 101 but was 401") },
                                { "must-not-run" },
                            ),
                        ),
                        authRecovery = transientRecovery,
                    )

                val result: AppResult<String> = catchingRpcResult { cache.call { AppResult.Success(it.work()) } }

                val error = result.shouldBeInstanceOf<AppResult.Failure>().error
                // Retryable transport error — NOT SessionExpired, so the app stays signed in.
                error.shouldBeInstanceOf<TransportError.NetworkUnavailable>()
                error.isRetryable shouldBe true
                transientRecovery.count shouldBe 1
                connects() shouldBe 1 // no retry against the same dead socket
            }
        }

        test("C1: a timeout drops only the proxy — the shared client survives so in-flight siblings are not torn down") {
            runTest {
                // The connect lambda captures the derived `.config { }` child the whole channel shares, so
                // the test can assert the timeout did NOT close it (closing it would cancel every WS session
                // riding it — the sibling-teardown bug C1 fixes). No new proxy behavior is scripted: the
                // sibling parks in its own block, the timing-out call's work() hangs forever.
                val capturedClients = mutableListOf<HttpClient>()
                var connectCount = 0
                val siblingCanFinish = CompletableDeferred<Unit>()
                val cache =
                    RpcProxyCache(mockFactory(), mockServerConfig()) { client, _ ->
                        connectCount++
                        capturedClients += client
                        FakeProxy { awaitCancellation() }
                    }

                // A sibling call leases the shared proxy/client and parks IN FLIGHT inside its own block.
                val sibling =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        cache.call {
                            siblingCanFinish.await()
                            "sibling-ok"
                        }
                    }

                // Another call on the SAME channel trips our own bound → OutcomeUnknown (drop-proxy-only).
                shouldThrow<RpcOutcomeUnknownException> {
                    cache.call(timeout = 50.milliseconds) { it.work() }
                }

                // The shared derived client the sibling rides was NOT closed by the timeout.
                capturedClients.single().isActive shouldBe true

                // And the sibling completes normally — never torn down.
                siblingCanFinish.complete(Unit)
                sibling.await() shouldBe "sibling-ok"
                connectCount shouldBe 1 // one shared proxy/client served both
            }
        }

        // ─── B2: the idempotent-call knob ────────────────────────────────────────────────────
        //
        // A post-delivery lost response (a from-below "Client cancelled" CE, or our own first-attempt
        // timeout) is outcome-unknown for a MUTATION — re-firing could double-apply. For a READ,
        // re-firing is always safe, so a caller may declare `idempotent = true` to auto-retry ONCE on
        // a fresh lease. The default (`idempotent = false`) is unchanged: surface, never re-fire.

        test("idempotent = true retries once on a from-below (post-delivery) cancellation and returns the retry Success") {
            runTest {
                val (cache, connects) =
                    scriptedCache(
                        ArrayDeque(
                            listOf(
                                { throw CancellationException("Client cancelled") },
                                { "healed" },
                            ),
                        ),
                    )

                cache.call(idempotent = true) { it.work() } shouldBe "healed"
                connects() shouldBe 2 // 1 original (lost response) + exactly 1 idempotent retry
            }
        }

        test("idempotent = true retries once on a first-attempt timeout and returns the retry Success") {
            runTest {
                var calls = 0
                val (cache, connects) =
                    scriptedCache(
                        ArrayDeque(
                            listOf(
                                {
                                    calls++
                                    awaitCancellation()
                                },
                                { "healed" },
                            ),
                        ),
                    )

                cache.call(timeout = 50.milliseconds, idempotent = true) { it.work() } shouldBe "healed"
                calls shouldBe 1 // the first (hung) behavior ran once; the retry took the second
                connects() shouldBe 2 // 1 original (timed out) + exactly 1 idempotent retry
            }
        }

        test("idempotent = true is at-most-once: a SECOND from-below cancellation after the retry surfaces OutcomeUnknown") {
            runTest {
                val (cache, connects) =
                    scriptedCache(
                        ArrayDeque(
                            listOf(
                                { throw CancellationException("Client cancelled") },
                                { throw CancellationException("Client cancelled") },
                                { "must-not-run" },
                            ),
                        ),
                    )

                val result: AppResult<String> =
                    catchingRpcResult { cache.call(idempotent = true) { AppResult.Success(it.work()) } }

                result
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<TransportError.OutcomeUnknown>()
                connects() shouldBe 2 // original + one retry only — the third behavior is never reached
            }
        }

        test("idempotent = true is at-most-once: a SECOND timeout after the retry surfaces OutcomeUnknown") {
            runTest {
                var calls = 0
                val (cache, connects) =
                    scriptedCache(
                        ArrayDeque(
                            listOf(
                                {
                                    calls++
                                    awaitCancellation()
                                },
                                {
                                    calls++
                                    awaitCancellation()
                                },
                                { "must-not-run" },
                            ),
                        ),
                    )

                val result: AppResult<String> =
                    catchingRpcResult {
                        cache.call(timeout = 50.milliseconds, idempotent = true) { AppResult.Success(it.work()) }
                    }

                result
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<TransportError.OutcomeUnknown>()
                calls shouldBe 2 // the original and the single retry both hung — no third fire
                connects() shouldBe 2
            }
        }

        test("idempotent = false (default) still surfaces OutcomeUnknown with NO retry — the no-double-apply guard is intact") {
            runTest {
                val (cache, connects) =
                    scriptedCache(
                        ArrayDeque(
                            listOf(
                                { throw CancellationException("Client cancelled") },
                                { "must-not-run" },
                            ),
                        ),
                    )

                val result: AppResult<String> =
                    catchingRpcResult { cache.call(idempotent = false) { AppResult.Success(it.work()) } }

                result
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<TransportError.OutcomeUnknown>()
                connects() shouldBe 1 // NO retry — the mutation guard holds for the default
            }
        }
    })
