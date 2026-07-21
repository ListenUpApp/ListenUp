package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.data.remote.ApiClientFactory
import com.calypsan.listenup.client.domain.repository.ServerConfig
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Proves the registration-policy SSE stream inherits the [com.calypsan.listenup.client.data.sync.SseConnection]
 * engine's guarantees after the Phase-A extraction: a bounded connect (no iOS silent hang),
 * exponential reconnect (no lost policy-change events on a mid-wait server restart), and
 * spec-tolerant `data:` framing (the mandatory-space parser bug is gone).
 *
 * The sibling registration-STATUS stream moved to the terminal-completing RPC watch
 * (`AuthServicePublic.observeRegistrationStatus`, see `RegistrationStreamRpcTest`) — the
 * registration-policy toggle (the Sign Up switch on the login screen) is a genuinely long-lived,
 * server-holds-the-connection-open stream, so it stays on SSE.
 */
class RegistrationPolicyStreamSseTest :
    FunSpec({

        fun factoryFor(client: HttpClient): ApiClientFactory =
            mock<ApiClientFactory> {
                everySuspend { getUnauthenticatedStreamingClient() } returns client
            }

        fun serverConfig(): ServerConfig =
            mock<ServerConfig> {
                everySuspend { getServerUrl() } returns ServerUrl("http://test")
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
