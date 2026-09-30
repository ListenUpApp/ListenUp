package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.core.ServerUrl
import dev.mokkery.answering.calls
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.mock
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.websocket.WebSocketException
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The lifecycle of the kotlinx.rpc clients behind [RpcProxyCache]'s proxies — each one a WebSocket.
 *
 * `client.rpc(url)` opens its own socket, and closing the `HttpClient` it came from does not close
 * it, so a proxy the cache drops without closing its client leaks a socket. A browser tab whose
 * throttled timers kept tripping the call bound piled up 30 of them until every new handshake failed
 * and the app read "Server offline". These pin the rule that fixes it: a dropped connection is
 * RETIRED, and a retired connection closes the moment nothing is using it — never sooner, so a call
 * or stream still riding it (C1's sibling, the firehose across a reconnect sweep) is not torn down.
 *
 * [connect] hands out fake connections from a [Ledger] that records which ones were closed; no real
 * socket is opened.
 */
class RpcProxyCacheConnectionLifecycleTest :
    FunSpec({

        /** A scripted proxy: a unary [work] and a server-push [events] stream. */
        class FakeProxy(
            private val onWork: suspend () -> String = { awaitCancellation() },
            private val onEvents: () -> Flow<String> = { emptyFlow() },
        ) {
            suspend fun work(): String = onWork()

            fun events(): Flow<String> = onEvents()
        }

        /** Numbers each connection [connect] opens, in order, and records which of them were closed. */
        class Ledger {
            var opened = 0
                private set
            val closed = mutableSetOf<Int>()
            val stillOpen: List<Int> get() = (0 until opened).filterNot { it in closed }

            fun connection(proxy: FakeProxy): RpcConnection<FakeProxy> {
                val id = opened++
                return RpcConnection(proxy) { closed += id }
            }
        }

        fun mockFactory(): ApiClientFactory =
            mock {
                everySuspend { getClient() } calls { HttpClient(MockEngine { respond("") }) { install(WebSockets) } }
            }

        fun mockServerConfig(): ServerConfig = mock { everySuspend { getActiveUrl() } returns ServerUrl("http://localhost") }

        /** A cache whose Nth connection serves the Nth of [proxies]; the default is a proxy whose work hangs. */
        fun ledgerCache(
            ledger: Ledger,
            vararg proxies: FakeProxy,
        ): RpcProxyCache<FakeProxy> {
            val script = ArrayDeque(proxies.toList())
            return RpcProxyCache(
                apiClientFactory = mockFactory(),
                serverConfig = mockServerConfig(),
                preDeliveryRetryBackoff = Duration.ZERO,
            ) { _, _ -> ledger.connection(script.removeFirstOrNull() ?: FakeProxy()) }
        }

        test("five consecutive call timeouts leave no connection open behind them") {
            runTest {
                val ledger = Ledger()
                val cache = ledgerCache(ledger)

                repeat(5) {
                    shouldThrow<RpcOutcomeUnknownException> {
                        cache.call(timeout = 50.milliseconds) { it.work() }
                    }
                }

                // Each timeout re-leased a fresh proxy — and nothing was left using the one it dropped.
                ledger.opened shouldBe 5
                ledger.stillOpen.shouldBeEmpty()
            }
        }

        test("a call in flight on a timed-out connection keeps it open until it finishes, then it closes") {
            runTest {
                val ledger = Ledger()
                val inFlightCanFinish = CompletableDeferred<Unit>()
                val cache = ledgerCache(ledger)

                val inFlight =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        cache.call {
                            inFlightCanFinish.await()
                            "in-flight-ok"
                        }
                    }
                shouldThrow<RpcOutcomeUnknownException> {
                    cache.call(timeout = 50.milliseconds) { it.work() }
                }

                // Retired, but still carrying the sibling's call (C1) — closing now would cancel it.
                ledger.stillOpen shouldContainExactly listOf(0)

                inFlightCanFinish.complete(Unit)
                inFlight.await() shouldBe "in-flight-ok"
                ledger.stillOpen.shouldBeEmpty()
            }
        }

        test("a stream outlives a timeout on its connection, which closes when the stream completes") {
            runTest {
                val ledger = Ledger()
                val events = Channel<String>(Channel.UNLIMITED)
                val cache =
                    ledgerCache(
                        ledger,
                        FakeProxy(onEvents = { events.receiveAsFlow() }),
                        FakeProxy(onWork = { "fresh" }),
                    )
                val received = mutableListOf<String>()
                val stream = launch { cache.streaming { it.events() }.collect { received += it } }
                events.send("a")
                runCurrent()

                shouldThrow<RpcOutcomeUnknownException> {
                    cache.call(timeout = 50.milliseconds) { it.work() }
                }
                cache.call { it.work() } shouldBe "fresh"

                // The stream keeps receiving on the first connection, which stays open under it.
                events.send("b")
                runCurrent()
                received shouldContainExactly listOf("a", "b")
                ledger.stillOpen shouldContainExactly listOf(0, 1)

                events.close()
                stream.join()
                ledger.stillOpen shouldContainExactly listOf(1)
            }
        }

        test("a stream outlives a timeout on its connection, which closes when the stream is cancelled") {
            runTest {
                val ledger = Ledger()
                val cache = ledgerCache(ledger, FakeProxy(onEvents = { flow { awaitCancellation() } }))
                val stream = launch { cache.streaming { it.events() }.collect { } }
                runCurrent()

                shouldThrow<RpcOutcomeUnknownException> {
                    cache.call(timeout = 50.milliseconds) { it.work() }
                }
                ledger.stillOpen shouldContainExactly listOf(0)

                stream.cancel()
                stream.join()
                ledger.stillOpen.shouldBeEmpty()
            }
        }

        test("invalidate closes the current connection and every retired one") {
            runTest {
                val ledger = Ledger()
                val inFlightCanFinish = CompletableDeferred<Unit>()
                val cache = ledgerCache(ledger, FakeProxy(), FakeProxy(), FakeProxy(onWork = { "current" }))

                // Connection 0 is retired while a call still rides it; connection 1 is retired idle.
                val inFlight = async(start = CoroutineStart.UNDISPATCHED) { cache.call { inFlightCanFinish.await() } }
                shouldThrow<RpcOutcomeUnknownException> { cache.call(timeout = 50.milliseconds) { it.work() } }
                shouldThrow<RpcOutcomeUnknownException> { cache.call(timeout = 50.milliseconds) { it.work() } }
                cache.call { it.work() } shouldBe "current"

                cache.invalidate()

                // Everything idle is closed at once; the one still carrying a call closes as it ends.
                ledger.stillOpen shouldContainExactly listOf(0)
                inFlightCanFinish.complete(Unit)
                inFlight.await()
                ledger.stillOpen.shouldBeEmpty()
            }
        }

        test("a full drop under a live stream spares it — the firehose reconnect sweep must not abort the firehose") {
            runTest {
                // ConnectionCoordinator's reconnect sweep full-invalidates EVERY channel, including the one
                // the just-reconnected firehose rides. Closing that connection would kill the stream whose
                // reconnect triggered the sweep, and loop. It retires instead, and closes when the stream ends.
                val ledger = Ledger()
                val events = Channel<String>(Channel.UNLIMITED)
                val cache = ledgerCache(ledger, FakeProxy(onEvents = { events.receiveAsFlow() }))
                val received = mutableListOf<String>()
                val stream = launch { cache.streaming { it.events() }.collect { received += it } }
                runCurrent()

                cache.invalidate()
                events.send("after-sweep")
                runCurrent()

                received shouldContainExactly listOf("after-sweep")
                ledger.stillOpen shouldContainExactly listOf(0)

                events.close()
                stream.join()
                ledger.stillOpen.shouldBeEmpty()
            }
        }

        test("a pre-delivery failure closes the dead connection once the retry has moved off it") {
            runTest {
                val ledger = Ledger()
                val cache =
                    ledgerCache(
                        ledger,
                        FakeProxy(onWork = { throw WebSocketException("handshake dropped") }),
                        FakeProxy(onWork = { "retried" }),
                    )

                cache.call { it.work() } shouldBe "retried"

                ledger.stillOpen shouldContainExactly listOf(1)
            }
        }

        test("a stream that resubscribes closes the connection it left behind, not the one it moved to") {
            runTest {
                val ledger = Ledger()
                val events = Channel<String>(Channel.UNLIMITED)
                val cache =
                    ledgerCache(
                        ledger,
                        FakeProxy(onEvents = { flow { throw WebSocketException("handshake dropped") } }),
                        FakeProxy(onEvents = { events.receiveAsFlow() }),
                    )
                val received = mutableListOf<String>()
                val stream = launch { cache.streaming { it.events() }.collect { received += it } }
                events.send("healed")
                runCurrent()

                received shouldContainExactly listOf("healed")
                ledger.stillOpen shouldContainExactly listOf(1)

                // The resubscribed connection is the live one — ending the stream leaves it for the next lease.
                stream.cancel()
                stream.join()
                ledger.stillOpen shouldContainExactly listOf(1)
            }
        }

        test("a connection whose close throws never throws into the caller") {
            runTest {
                val cache =
                    RpcProxyCache(
                        apiClientFactory = mockFactory(),
                        serverConfig = mockServerConfig(),
                    ) { _, _ -> RpcConnection(FakeProxy()) { error("close blew up") } }

                // The retirement closes the connection inside the timeout path; the caller still sees
                // the typed outcome, not the close's own failure.
                shouldThrow<RpcOutcomeUnknownException> {
                    cache.call(timeout = 50.milliseconds) { it.work() }
                }
            }
        }
    })
