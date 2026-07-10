package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.client.data.remote.installListenUpErrorHandling
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.concurrent.atomic.AtomicInteger
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
 * Locks in the fix that treats mid-stream 401/403 as transient rather than
 * terminal, so the Ktor Bearer Auth plugin can refresh the access token on the
 * next reconnect.
 *
 * Pre-fix (the regression this test prevents): `runOnce()` returned
 * `AuthFailed` and the outer reconnect loop called `return@launch`, stranding
 * production Books-A users every 15 minutes (access-token TTL) until app
 * restart. Post-fix: `AuthFailed` records the disconnect reason and falls
 * through to the same backoff+retry path as a transient network error.
 *
 * The contract being pinned: a single 401 must NOT leave the SSE client in a
 * permanent terminal state. The reconnect path must run so the auth plugin
 * gets another outbound request to attach a refreshed token to.
 *
 * Tested at the [SyncSseClient] level with a `MockEngine`-backed `HttpClient`
 * configured with `installListenUpErrorHandling()` (mirroring production's
 * `expectSuccess = true`). This decouples the regression test from the real
 * server's auth implementation, matches the test style of
 * `SyncCatchUpClientTest`, and runs without spinning up `:server` for a pure
 * client-side invariant.
 */
class SyncSseClientAuthRefreshTest :
    FunSpec({

        test("401 mid-stream surfaces Disconnected(\"auth\") then reconnects to Connected — not permanent terminal") {
            runBlocking {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val attempts = AtomicInteger(0)
                val client =
                    HttpClient(
                        MockEngine { _ ->
                            if (attempts.incrementAndGet() == 1) {
                                respondError(HttpStatusCode.Unauthorized)
                            } else {
                                respondSse(SSE_FRAME_ID_1)
                            }
                        },
                    ) {
                        installListenUpErrorHandling()
                    }

                try {
                    val state = SyncEngineState()
                    val sse =
                        SyncSseClient(
                            serverUrlProvider = { "" },
                            streamingClientProvider = { client },
                            state = state,
                            scope = scope,
                        )

                    sse.connect()

                    // Counterfactual proof we exercised the AuthFailed branch: the
                    // state must pass through Disconnected with reason AUTH_REASON
                    // at some point. With the pre-fix `return@launch`, the outer loop
                    // would have exited *before* setting that reason, OR (if it set
                    // Disconnected("closed") in `disconnect()`) we'd see a different
                    // reason — either way, AUTH_REASON only appears on the new
                    // transient path.
                    withTimeout(AUTH_TRANSIENT_TIMEOUT) {
                        state
                            .observe()
                            .filter {
                                (it.connection as? ConnectionState.Disconnected)?.reason == AUTH_REASON
                            }.first()
                    }

                    // After backoff (1s for attempt 0), the second attempt 200s and
                    // we reach Connected. With the pre-fix terminal `return@launch`,
                    // this would time out: the outer loop never made a second attempt.
                    // Assert the AWAITED Connected value, not a later re-read of
                    // state.value: the single-frame mock EOFs after one frame, so the
                    // client legitimately reconnects again — re-reading state.value here
                    // would race that churn (the source of CI flakiness).
                    val connected =
                        withTimeout(RECONNECT_TIMEOUT) {
                            state.observe().filter { it.connection is ConnectionState.Connected }.first()
                        }
                    connected.connection.shouldBeInstanceOf<ConnectionState.Connected>()
                    // The invariant is "the 401 was transient — the client made another
                    // attempt rather than terminating", not an exact count. The mock's
                    // post-401 frame EOFs and can drive further reconnects, so assert >= 2
                    // to stay deterministic under slow CI.
                    attempts.get() shouldBeGreaterThanOrEqual 2
                } finally {
                    scope.cancel()
                    client.close()
                }
            }
        }

        test("403 (Forbidden) is treated as auth-transient — same recovery path as 401") {
            runBlocking {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val attempts = AtomicInteger(0)
                val client =
                    HttpClient(
                        MockEngine { _ ->
                            if (attempts.incrementAndGet() == 1) {
                                respondError(HttpStatusCode.Forbidden)
                            } else {
                                respondSse(SSE_FRAME_ID_1)
                            }
                        },
                    ) {
                        installListenUpErrorHandling()
                    }

                try {
                    val state = SyncEngineState()
                    val sse =
                        SyncSseClient(
                            serverUrlProvider = { "" },
                            streamingClientProvider = { client },
                            state = state,
                            scope = scope,
                        )

                    sse.connect()

                    withTimeout(AUTH_TRANSIENT_TIMEOUT) {
                        state
                            .observe()
                            .filter {
                                (it.connection as? ConnectionState.Disconnected)?.reason == AUTH_REASON
                            }.first()
                    }
                    withTimeout(RECONNECT_TIMEOUT) {
                        state.observe().filter { it.connection is ConnectionState.Connected }.first()
                    }
                    // See the 401 case: assert >= 2 (a reconnect happened), not an exact
                    // count the single-frame mock can exceed via post-EOF reconnect churn.
                    attempts.get() shouldBeGreaterThanOrEqual 2
                } finally {
                    scope.cancel()
                    client.close()
                }
            }
        }
    })

private const val SSE_FRAME_ID_1 = 1L
private const val AUTH_REASON = "auth"
private val AUTH_TRANSIENT_TIMEOUT = 10.seconds
private val RECONNECT_TIMEOUT = 15.seconds

/**
 * Single SSE frame, terminated by the blank line the parser needs to commit the
 * frame. The content body shape doesn't matter for this test — only that the
 * connection succeeds, the parser commits, and `lastEventId` advances.
 */
private fun io.ktor.client.engine.mock.MockRequestHandleScope.respondSse(eventId: Long) =
    respond(
        content =
            """
            id: $eventId
            event: heartbeat
            data: {}


            """.trimIndent(),
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
    )
