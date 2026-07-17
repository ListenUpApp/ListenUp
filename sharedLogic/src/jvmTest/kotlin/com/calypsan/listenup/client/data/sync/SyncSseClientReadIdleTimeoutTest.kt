package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.client.data.remote.installListenUpErrorHandling
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Pins the read-idle timeout that lets the SSE loop recover from a **half-open** connection.
 *
 * The regression this prevents is the nastiest failure mode in the reconnect stack, because it is
 * silent. A NAT rebind, Wi-Fi AP roam, router restart, or a buffering reverse proxy can kill the
 * TCP path without an RST: the socket is dead, but nothing tells the client. The streaming read was
 * deliberately unbounded ("servers heartbeat well within any read window"), so `readLine()` simply
 * blocked forever. `SyncEngineState` stayed `Connected`, which meant:
 *
 *  - `ReconnectionSupervisor` never probed (its recovery loop is gated on not-connected),
 *  - the connection-up edge never fired, so the outbox never drained,
 *  - `ConnectionHealthStore` reported Healthy — and the supervisor's HTTP probe *succeeds* on a
 *    half-open stream, so it actively masked the offline banner.
 *
 * The app looked online and received nothing, indefinitely, while foregrounded.
 *
 * The server already sends the signal needed to detect this: a comment-line keepalive every 25s
 * (`SyncRoutes.heartbeatIntervalMillis`). The client parsed those comment lines and *discarded*
 * them without touching any liveness state. Now every read is bounded by [READ_IDLE_TIMEOUT_MS] —
 * comfortably more than the heartbeat interval, so a live-but-idle stream is never torn down, while
 * a dead one is detected and reconnected.
 *
 * Simulated with a MockEngine that returns a 200 SSE response whose body channel stays open and
 * silent forever — the exact shape of a half-open socket.
 */
class SyncSseClientReadIdleTimeoutTest :
    FunSpec({
        test("a connected stream that goes silent past the idle window drives a reconnect, not an infinite hang") {
            runBlocking {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                // A body channel that is open but never written to: the response arrives (so the
                // connect watchdog is satisfied and the loop reaches Connected), then nothing.
                val silentClient =
                    HttpClient(
                        MockEngine { _ ->
                            respond(
                                content = ByteChannel(autoFlush = true),
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
                            )
                        },
                    ) {
                        installListenUpErrorHandling()
                    }

                try {
                    val state = SyncEngineState()
                    val sse =
                        SyncSseClient(
                            serverUrlProvider = { "" },
                            streamingClientProvider = { silentClient },
                            state = state,
                            scope = scope,
                            readIdleTimeoutMillis = IDLE_TIMEOUT_MS,
                        )

                    sse.connect()

                    // Pre-fix this parks in `Connected` forever — the read never returns and the
                    // whole recovery stack stands down behind a lie. Post-fix the idle watchdog
                    // fires and the loop reports Disconnected and backs off.
                    val reconnecting =
                        withTimeout(GUARD_TIMEOUT) {
                            state
                                .observe()
                                .filter { (it.connection as? ConnectionState.Disconnected)?.reason == RECONNECTING }
                                .first()
                        }
                    (reconnecting.connection as ConnectionState.Disconnected).reason shouldBe RECONNECTING
                } finally {
                    scope.cancel()
                    silentClient.close()
                }
            }
        }
    })

private const val RECONNECTING = "reconnecting"
private const val IDLE_TIMEOUT_MS = 300L
private val GUARD_TIMEOUT = 10.seconds
