package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.client.data.remote.installListenUpErrorHandling
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Pins the connect-phase timeout that lets the SSE reconnect loop recover after
 * the server goes down or unreachable.
 *
 * Regression this prevents: the iOS (Darwin/URLSession) streaming client had
 * infinite timeouts on every phase including connect, so a reconnect attempt
 * against a down server hung inside `execute {}` forever — [SyncSseClient.runOnce]
 * never returned, the connection state never left `Connecting`, and the
 * `ReconnectionSupervisor` (gated on not-connected) never ran. The user-visible
 * symptom: iOS never reconnects after the server comes back, while Android (a
 * finite OkHttp connect timeout) does.
 *
 * The fix moves the connect bound into commonMain so both platforms behave
 * identically: a connection that doesn't produce a response within
 * `connectTimeoutMillis` is abandoned and classified as `Reconnect`, so the loop
 * cycles. The streaming read itself stays unbounded — a live-but-idle SSE stream
 * must not be torn down (both servers heartbeat well within any read window).
 *
 * Simulated with a `MockEngine` handler that never responds
 * ([awaitCancellation]) — a server that accepts the socket but never sends the
 * response, the exact shape that used to hang forever on Darwin.
 */
class SyncSseClientConnectTimeoutTest :
    FunSpec({
        test("a connect that never produces a response is abandoned and drives a reconnect, not an infinite hang") {
            runBlocking {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val hangingClient =
                    HttpClient(MockEngine { _ -> awaitCancellation() }) {
                        installListenUpErrorHandling()
                    }

                try {
                    val state = SyncEngineState()
                    val sse =
                        SyncSseClient(
                            serverUrlProvider = { "" },
                            streamingClientProvider = { hangingClient },
                            state = state,
                            scope = scope,
                            connectTimeoutMillis = CONNECT_TIMEOUT_MS,
                        )

                    sse.connect()

                    // Pre-fix this hangs at Connecting forever (the Darwin infinite-timeout
                    // bug), so awaiting the reconnecting state times out. Post-fix the connect
                    // watchdog fires after CONNECT_TIMEOUT_MS and the loop enters its backoff.
                    val reconnecting =
                        withTimeout(GUARD_TIMEOUT) {
                            state
                                .observe()
                                .filter {
                                    (it.connection as? ConnectionState.Disconnected)?.reason == RECONNECTING
                                }.first()
                        }
                    (reconnecting.connection as ConnectionState.Disconnected).reason shouldBe RECONNECTING
                } finally {
                    scope.cancel()
                    hangingClient.close()
                }
            }
        }
    })

private const val RECONNECTING = "reconnecting"
private const val CONNECT_TIMEOUT_MS = 300L
private val GUARD_TIMEOUT = 10.seconds
