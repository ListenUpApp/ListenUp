package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.InstanceService
import com.calypsan.listenup.client.di.e2e.TestServerConfig
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.withService
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import io.ktor.server.websocket.WebSockets as ServerWebSockets

/** How long an upgrade the server has let go of is given to open its session — loopback takes milliseconds. */
private val UPGRADE_LANDING = 1.seconds

/**
 * Real sockets against a real in-process server: after the client gives up on a connection, the
 * server must see no WebSocket session left open behind it.
 *
 * The server is deliberately NOT a kRPC server. Its `/api/rpc/public` mount accepts the WebSocket and
 * then says nothing, so a kRPC client's handshake never completes — the shape of a stalled server —
 * and it counts the sessions it currently holds open. Optionally it stalls the UPGRADE itself behind
 * a gate, the shape of a slow network: kotlinx.rpc opens its transport lazily inside the first call,
 * so a call bound that trips during the upgrade cancels only the waiter, while Ktor's
 * `webSocketSession` finishes the upgrade in the HttpClient's own scope and parks the session there.
 * `KtorRpcClient.close()` cannot reach that session (its transport never became ready), so only
 * cancelling the HttpClient that launched it does.
 *
 * `runBlocking`, not `runTest`: real I/O on the real clock.
 */
class RpcSocketLeakE2ETest :
    FunSpec({

        /**
         * A silent WebSocket server that can hold the upgrade behind [upgradeGate], and counts: upgrade
         * requests that reached the gate, those the gate has let go of (passed, or abandoned by a client
         * that hung up while held), sessions ever opened, and sessions open right now.
         */
        class SilentServer(
            val upgradeGate: CompletableDeferred<Unit> = CompletableDeferred(Unit),
        ) {
            val upgradesReached = AtomicInteger(0)
            val upgradesReleased = AtomicInteger(0)
            val sessionsOpened = AtomicInteger(0)
            val openSessions = AtomicInteger(0)
            private val stallUpgrade =
                createRouteScopedPlugin("StallUpgrade") {
                    onCall {
                        upgradesReached.incrementAndGet()
                        try {
                            upgradeGate.await()
                        } finally {
                            upgradesReleased.incrementAndGet()
                        }
                    }
                }
            val server: EmbeddedServer<*, *> =
                embeddedServer(CIO, port = 0, host = "127.0.0.1") {
                    install(ServerWebSockets)
                    routing {
                        route("/api/rpc/public") {
                            install(stallUpgrade)
                            webSocket {
                                sessionsOpened.incrementAndGet()
                                openSessions.incrementAndGet()
                                try {
                                    for (frame in incoming) Unit
                                } finally {
                                    openSessions.decrementAndGet()
                                }
                            }
                        }
                    }
                }.start(wait = false)

            val port: Int get() =
                runBlocking {
                    server.engine
                        .resolvedConnectors()
                        .first()
                        .port
                }

            fun stop() = server.stop(gracePeriodMillis = 100, timeoutMillis = 500)
        }

        /** An [ApiClientFactory] that serves one fixed client, as the in-process E2E tests do. */
        class FixedApiClientFactory(
            private val client: HttpClient,
        ) : ApiClientFactory {
            override suspend fun getClient(): HttpClient = client

            override suspend fun currentAccessToken(): String? = null

            override suspend fun invalidateRequestClientOnly() = Unit

            override suspend fun warmUp() = Unit

            override suspend fun invalidate() = Unit
        }

        /** Poll until [counter] reaches at least [atLeast] or [within] elapses; returns the last reading. */
        suspend fun awaitCount(
            counter: AtomicInteger,
            atLeast: Int,
            within: Duration = 10.seconds,
        ): Int {
            val deadline = TimeSource.Monotonic.markNow() + within
            while (counter.get() < atLeast && deadline.hasNotPassedNow()) delay(20)
            return counter.get()
        }

        /** Poll until [openSessions] reads [expected] or [within] elapses; returns the last reading. */
        suspend fun settle(
            openSessions: AtomicInteger,
            expected: Int,
            within: Duration = 5.seconds,
        ): Int {
            val deadline = TimeSource.Monotonic.markNow() + within
            while (openSessions.get() != expected && deadline.hasNotPassedNow()) delay(50)
            return openSessions.get()
        }

        fun publicCache(baseUrl: String): RpcProxyCache<InstanceService> =
            RpcProxyCache(
                FixedApiClientFactory(HttpClient(OkHttp) { install(WebSockets) }),
                TestServerConfig(baseUrl),
            ) { client, url -> client.rpc("$url/api/rpc/public").asConnection { withService<InstanceService>() } }

        test("a call that times out during the WebSocket upgrade leaves no session open once the upgrade lands") {
            runBlocking {
                val silent = SilentServer(upgradeGate = CompletableDeferred())
                try {
                    val cache = publicCache("http://127.0.0.1:${silent.port}")

                    shouldThrow<RpcOutcomeUnknownException> {
                        cache.call(timeout = 500.milliseconds) { it.getServerInfo() }
                    }
                    awaitCount(silent.upgradesReached, atLeast = 1) shouldBe 1

                    // The upgrade the timed-out call started now completes on the server.
                    silent.upgradeGate.complete(Unit)

                    // Only once the server has let the held request go can a leaked upgrade land; then give
                    // it time to open before reading the count — a poll for zero would pass instantly.
                    awaitCount(silent.upgradesReleased, atLeast = 1) shouldBe 1
                    delay(UPGRADE_LANDING)
                    silent.openSessions.get() shouldBe 0
                } finally {
                    silent.stop()
                }
            }
        }

        test("a call that times out after the upgrade leaves no session open") {
            runBlocking {
                val silent = SilentServer()
                try {
                    val cache = publicCache("http://127.0.0.1:${silent.port}")

                    shouldThrow<RpcOutcomeUnknownException> {
                        cache.call(timeout = 500.milliseconds) { it.getServerInfo() }
                    }

                    // The session did open (so a zero below means it closed, not that it never came)…
                    awaitCount(silent.sessionsOpened, atLeast = 1) shouldBe 1
                    settle(silent.openSessions, expected = 0) shouldBe 0
                } finally {
                    silent.stop()
                }
            }
        }

        test("per-connection clients neither kill the shared engine nor keep it alive once all are closed") {
            runBlocking {
                val silent = SilentServer()
                try {
                    // A factory-built client manages its engine by reference count: every client derived
                    // from it takes a reference, and the engine closes when the last one lets go.
                    val shared = HttpClient(OkHttp) { install(WebSockets) }
                    val cache =
                        RpcProxyCache(FixedApiClientFactory(shared), TestServerConfig("http://127.0.0.1:${silent.port}")) {
                            client,
                            url,
                            ->
                            client.rpc("$url/api/rpc/public").asConnection { withService<InstanceService>() }
                        }

                    repeat(3) {
                        shouldThrow<RpcOutcomeUnknownException> {
                            cache.call(timeout = 300.milliseconds) { it.getServerInfo() }
                        }
                    }

                    // Three connections were each derived, used, and cancelled — the engine served them all
                    // and is still running for the request client that owns it.
                    awaitCount(silent.upgradesReached, atLeast = 3) shouldBe 3
                    shared.engine.coroutineContext.job.isActive shouldBe true
                    awaitCount(silent.sessionsOpened, atLeast = 3) shouldBe 3
                    settle(silent.openSessions, expected = 0) shouldBe 0

                    // And nothing the cache let go of still pins it: closing the owner closes the engine.
                    shared.close()
                    withTimeout(5.seconds) {
                        shared.engine.coroutineContext.job
                            .join()
                    }
                } finally {
                    silent.stop()
                }
            }
        }

        test("a server probe that times out after the upgrade leaves no session open") {
            runBlocking {
                val silent = SilentServer()
                try {
                    val factory = KtorInstanceRpcFactory(requestTimeoutMillis = 800, socketTimeoutMillis = 800)

                    runCatching { factory.getServerInfo("ws://127.0.0.1:${silent.port}") }

                    awaitCount(silent.upgradesReached, atLeast = 1) shouldBe 1
                    awaitCount(silent.sessionsOpened, atLeast = 1) shouldBe 1
                    settle(silent.openSessions, expected = 0) shouldBe 0
                } finally {
                    silent.stop()
                }
            }
        }

        test("a server probe that times out during the upgrade leaves no session open once the upgrade lands") {
            runBlocking {
                val silent = SilentServer(upgradeGate = CompletableDeferred())
                try {
                    val factory = KtorInstanceRpcFactory(requestTimeoutMillis = 800, socketTimeoutMillis = 800)

                    runCatching { factory.getServerInfo("ws://127.0.0.1:${silent.port}") }
                    awaitCount(silent.upgradesReached, atLeast = 1) shouldBe 1

                    silent.upgradeGate.complete(Unit)

                    // Only once the server has let the held request go can a leaked upgrade land; then give
                    // it time to open before reading the count — a poll for zero would pass instantly.
                    awaitCount(silent.upgradesReleased, atLeast = 1) shouldBe 1
                    delay(UPGRADE_LANDING)
                    silent.openSessions.get() shouldBe 0
                } finally {
                    silent.stop()
                }
            }
        }
    })
