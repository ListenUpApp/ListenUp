package com.calypsan.listenup.client.data.remote

import app.cash.turbine.test
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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

/**
 * Unit tests for [RpcProxyCache.streaming] — the least-tested, most-subtle path on the RPC seam.
 *
 * The proxy is a scripted [FakeStreamProxy] whose `observe()` replays the next scripted cold flow, so
 * each test controls exactly how the subscription produces or fails. [connect] counts invocations so
 * reconnect / single-flight / no-invalidate behavior can be pinned precisely. The harness mirrors
 * [RpcProxyCacheCallTest]: a real MockEngine-backed [HttpClient] (so the `.config { installKrpc }`
 * derivation runs) and a fixed server URL; no real WebSocket is opened.
 */
class RpcProxyCacheStreamingTest :
    FunSpec({

        /** A scripted proxy whose single subscription replays [flowFactory] on every `observe()`. */
        class FakeStreamProxy(
            private val flowFactory: () -> kotlinx.coroutines.flow.Flow<String>,
        ) {
            fun observe(): kotlinx.coroutines.flow.Flow<String> = flowFactory()
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

        fun mockFactory(): ApiClientFactory =
            mock {
                everySuspend { getClient() } calls { HttpClient(MockEngine { respond("") }) { install(WebSockets) } }
            }

        fun mockServerConfig(): ServerConfig = mock { everySuspend { getActiveUrl() } returns ServerUrl("http://localhost") }

        /**
         * Build a cache whose [connect] pops the next scripted flow-factory and counts invocations.
         * The returned pair is (cache, connectCount-getter).
         */
        fun scriptedStreamCache(
            script: ArrayDeque<() -> kotlinx.coroutines.flow.Flow<String>>,
            authRecovery: RpcAuthRecovery = RpcAuthRecovery.None,
        ): Pair<RpcProxyCache<FakeStreamProxy>, () -> Int> {
            var connectCount = 0
            val cache =
                RpcProxyCache(mockFactory(), mockServerConfig(), authRecovery) { _, _ ->
                    connectCount++
                    FakeStreamProxy(script.removeFirst())
                }
            return cache to { connectCount }
        }

        test("a pre-first-event transport fault reconnects, resubscribes once, and delivers") {
            runTest {
                val (cache, connects) =
                    scriptedStreamCache(
                        ArrayDeque(
                            listOf(
                                { flow { throw WebSocketException("handshake dropped") } },
                                { flowOf("healed") },
                            ),
                        ),
                    )

                cache.streaming { it.observe() }.toList() shouldBe listOf("healed")
                connects() shouldBe 2 // 1 original (dead) + 1 resubscribe
            }
        }

        test("a pre-first-event handshake 401 refreshes the token and resubscribes") {
            runTest {
                val recovery = CountingAuthRecovery()
                val (cache, connects) =
                    scriptedStreamCache(
                        ArrayDeque(
                            listOf(
                                { flow { throw WebSocketException("expected status code 101 but was 401") } },
                                { flowOf("authed") },
                            ),
                        ),
                        authRecovery = recovery,
                    )

                cache.streaming { it.observe() }.toList() shouldBe listOf("authed")
                recovery.count shouldBe 1 // refreshed exactly once
                connects() shouldBe 2
            }
        }

        test("a mid-stream drop after the first event surfaces without auto-resubscribing (the emitted guard)") {
            runTest {
                val (cache, connects) =
                    scriptedStreamCache(
                        ArrayDeque(
                            listOf(
                                {
                                    flow {
                                        emit("one")
                                        throw WebSocketException("mid-stream drop")
                                    }
                                },
                                { flowOf("must-not-run") },
                            ),
                        ),
                    )

                cache.streaming { it.observe() }.test {
                    awaitItem() shouldBe "one"
                    awaitError().shouldBeInstanceOf<WebSocketException>()
                }
                connects() shouldBe 1 // no auto-resubscribe: the second behavior is never leased
            }
        }

        test("A2: a downstream truncation does NOT invalidate the generation nor surface OutcomeUnknown") {
            runTest {
                val (cache, connects) =
                    scriptedStreamCache(
                        ArrayDeque(
                            listOf(
                                {
                                    flow {
                                        emit("a")
                                        emit("b")
                                        emit("c")
                                    }
                                },
                                { flowOf("must-not-run") },
                            ),
                        ),
                    )

                // .first() truncates after "a": it throws AbortFlowException — a CancellationException on a
                // STILL-ACTIVE context — from the DOWNSTREAM collector. It must propagate cleanly (return
                // "a"), never be rewrapped as OutcomeUnknown or invalidate the healthy generation.
                cache.streaming { it.observe() }.first() shouldBe "a"

                // The generation stayed HEALTHY: a follow-up call reuses the SAME cached proxy — no reconnect.
                cache.call { it }
                connects() shouldBe 1
            }
        }

        test("a genuine caller cancellation of the stream re-raises without invalidating") {
            runTest {
                val (cache, connects) =
                    scriptedStreamCache(
                        ArrayDeque(
                            listOf(
                                {
                                    flow {
                                        emit("live")
                                        awaitCancellation()
                                    }
                                },
                            ),
                        ),
                    )

                val started = CompletableDeferred<Unit>()
                val job =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        cache.streaming { it.observe() }.collect { started.complete(Unit) }
                    }
                started.await() // first event delivered; the collector is now parked in the upstream flow
                job.cancel()
                job.join()

                job.isCancelled shouldBe true
                // Not invalidated by a genuine caller cancellation: a follow-up reuses the SAME cached proxy.
                cache.call { it }
                connects() shouldBe 1
            }
        }
    })
