package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.data.remote.ApiClientFactory
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.domain.repository.StreamedRegistrationStatus
import com.calypsan.listenup.core.ServerUrl
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Proves the two pre-auth registration SSE streams inherit the [com.calypsan.listenup.client.data.sync.SseConnection]
 * engine's guarantees after the Phase-A extraction: a bounded connect (no iOS silent hang),
 * exponential reconnect (no lost approval events on a mid-wait server restart), and spec-tolerant
 * `data:` framing (the mandatory-space parser bug is gone).
 *
 * Each test drives the real `*Impl` through a `MockEngine`-backed unauthenticated streaming client,
 * exactly how production wires it — so a regression in either stream, or in the shared engine, fails
 * here.
 */
class RegistrationStreamSseTest :
    FunSpec({

        fun factoryFor(client: HttpClient): ApiClientFactory =
            mock<ApiClientFactory> {
                everySuspend { getUnauthenticatedStreamingClient() } returns client
            }

        fun serverConfig(): ServerConfig =
            mock<ServerConfig> {
                everySuspend { getServerUrl() } returns ServerUrl("http://test")
            }

        test("status stream: a connect that never responds is abandoned and the loop reconnects — not an infinite hang") {
            runBlocking {
                val attempts = AtomicInteger(0)
                val client =
                    HttpClient(
                        MockEngine { _ ->
                            // First connect never produces a response (the exact Darwin silent-hang
                            // shape). Without the connect bound, streamStatus would wedge here forever.
                            if (attempts.incrementAndGet() == 1) awaitCancellation() else respondStatus("approved")
                        },
                    )
                try {
                    val impl =
                        RegistrationStatusStreamImpl(
                            apiClientFactory = factoryFor(client),
                            serverConfig = serverConfig(),
                            connectTimeoutMillis = CONNECT_TIMEOUT_MS,
                        )

                    // Pre-fix (no connect bound) this awaits forever; post-fix the watchdog abandons
                    // attempt 1, backs off, and the reconnect serves the approval.
                    val status =
                        withTimeout(GUARD_TIMEOUT) {
                            impl
                                .streamStatus("user-1")
                                .filter { it is StreamedRegistrationStatus.Approved }
                                .first()
                        }
                    status shouldBe StreamedRegistrationStatus.Approved
                    attempts.get() shouldBeGreaterThanOrEqual 2
                } finally {
                    client.close()
                }
            }
        }

        test("status stream: a mid-stream drop reconnects rather than terminally dying, delivering the later approval") {
            runBlocking {
                val attempts = AtomicInteger(0)
                val client =
                    HttpClient(
                        MockEngine { _ ->
                            // First connection: one pending frame then EOF (server restart drops the
                            // push channel). The old hand-rolled stream had NO reconnect — approval lost.
                            if (attempts.incrementAndGet() == 1) respondStatus("pending") else respondStatus("approved")
                        },
                    )
                try {
                    val impl =
                        RegistrationStatusStreamImpl(
                            apiClientFactory = factoryFor(client),
                            serverConfig = serverConfig(),
                            connectTimeoutMillis = CONNECT_TIMEOUT_MS,
                        )

                    val status =
                        withTimeout(GUARD_TIMEOUT) {
                            impl
                                .streamStatus("user-1")
                                .filter { it is StreamedRegistrationStatus.Approved }
                                .first()
                        }
                    status shouldBe StreamedRegistrationStatus.Approved
                    // >= 2 connects proves the stream re-subscribed after the first EOF.
                    attempts.get() shouldBeGreaterThanOrEqual 2
                } finally {
                    client.close()
                }
            }
        }

        test("status stream: a spec-legal `data:` frame WITHOUT a space parses (the old mandatory-space bug is gone)") {
            runBlocking {
                val client = HttpClient(MockEngine { _ -> respondStatusNoSpace("approved") })
                try {
                    val impl =
                        RegistrationStatusStreamImpl(
                            apiClientFactory = factoryFor(client),
                            serverConfig = serverConfig(),
                        )
                    val status =
                        withTimeout(GUARD_TIMEOUT) {
                            impl
                                .streamStatus("user-1")
                                .filter { it is StreamedRegistrationStatus.Approved }
                                .first()
                        }
                    status shouldBe StreamedRegistrationStatus.Approved
                } finally {
                    client.close()
                }
            }
        }

        test("policy stream: a connect that never responds is abandoned and reconnects — not an infinite hang") {
            runBlocking {
                val attempts = AtomicInteger(0)
                val client =
                    HttpClient(
                        MockEngine { _ ->
                            if (attempts.incrementAndGet() == 1) awaitCancellation() else respondPolicy("CLOSED")
                        },
                    )
                try {
                    val impl =
                        RegistrationPolicyStreamImpl(
                            apiClientFactory = factoryFor(client),
                            serverConfig = serverConfig(),
                            connectTimeoutMillis = CONNECT_TIMEOUT_MS,
                        )
                    val policy =
                        withTimeout(GUARD_TIMEOUT) {
                            impl.streamPolicy().first()
                        }
                    policy.name shouldBe "CLOSED"
                    attempts.get() shouldBeGreaterThanOrEqual 2
                } finally {
                    client.close()
                }
            }
        }

        test("policy stream: a spec-legal `data:` frame WITHOUT a space parses (the old mandatory-space bug is gone)") {
            runBlocking {
                val client = HttpClient(MockEngine { _ -> respondPolicyNoSpace("OPEN") })
                try {
                    val impl =
                        RegistrationPolicyStreamImpl(
                            apiClientFactory = factoryFor(client),
                            serverConfig = serverConfig(),
                        )
                    val policy = withTimeout(GUARD_TIMEOUT) { impl.streamPolicy().first() }
                    policy.name shouldBe "OPEN"
                } finally {
                    client.close()
                }
            }
        }
    })

private const val CONNECT_TIMEOUT_MS = 300L
private val GUARD_TIMEOUT = 10.seconds

private fun MockRequestHandleScope.respondSseBody(body: String) =
    respond(
        content = body,
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
    )

/** A single registration-status SSE frame with a spec-conventional `data: ` (space) prefix. */
private fun MockRequestHandleScope.respondStatus(status: String) =
    respondSseBody(
        """
        data: {"status":"$status"}


        """.trimIndent(),
    )

/** Same frame, but `data:` with NO space — spec-legal, and what the old parser silently dropped. */
private fun MockRequestHandleScope.respondStatusNoSpace(status: String) =
    respondSseBody(
        """
        data:{"status":"$status"}


        """.trimIndent(),
    )

private fun MockRequestHandleScope.respondPolicy(policy: String) =
    respondSseBody(
        """
        data: "$policy"


        """.trimIndent(),
    )

private fun MockRequestHandleScope.respondPolicyNoSpace(policy: String) =
    respondSseBody(
        """
        data:"$policy"


        """.trimIndent(),
    )
