package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.core.ServerUrl
import dev.mokkery.answering.calls
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.mock
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds

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

            override suspend fun refreshAndRebuild(): Boolean {
                count++
                return true
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
        ): Pair<RpcProxyCache<FakeProxy>, () -> Int> {
            var connectCount = 0
            val cache =
                RpcProxyCache(mockFactory(), mockServerConfig(), authRecovery) { _, _ ->
                    connectCount++
                    FakeProxy(script.removeFirst())
                }
            return cache to { connectCount }
        }

        test("dead-proxy CancellationException with a live caller reconnects and returns the retry result") {
            runTest {
                val (cache, connects) =
                    scriptedCache(
                        ArrayDeque(
                            listOf(
                                { throw CancellationException("RpcClient was cancelled") },
                                { "healed" },
                            ),
                        ),
                    )

                val result = cache.call { it.work() }

                result shouldBe "healed"
                connects() shouldBe 2 // 1 original (dead) + 1 reconnect
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
                // And the proxy was never invalidated: get() reuses the SAME cached proxy, connect stays 1.
                cache.get()
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
                } catch (_: CancellationException) {
                    // TimeoutCancellationException is a CancellationException subtype.
                    timedOut = true
                }

                timedOut shouldBe true
                calls shouldBe 1 // called exactly once — no auto-retry (no double-mutation)
                // The proxy WAS invalidated for the next attempt: a follow-up reconnects.
                cache.call { it.work() } shouldBe "reconnected"
                connects() shouldBe 2
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
                                    throw CancellationException("RpcClient was cancelled")
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

                val result: AppResult<String> = cache.rpcCall { AppResult.Success(it.work()) }

                result
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<AuthError.SessionExpired>()
                recovery.count shouldBe 1 // refreshed exactly once (not again on the retry)
                connects() shouldBe 2
            }
        }
    })
