package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.AuthServicePublic
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.dto.auth.RegistrationStatusEvent
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.ktor.client.rpcConfig
import kotlinx.rpc.krpc.serialization.json.json
import kotlinx.rpc.withService

/**
 * End-to-end proof of the terminal-completing registration-status RPC flow (public channel).
 *
 * This is the regression guard for the reconnect flood: the legacy SSE route served one frame
 * and closed the HTTP connection, which the retired client SSE engine's
 * `delivered > 0 → Established` heuristic mistook for a healthy stream, reconnecting with zero
 * backoff forever. `observeRegistrationStatus` fixes the ambiguity structurally: it emits the
 * current status, then live updates, and **completes** the moment the status turns terminal — a
 * completed [kotlinx.coroutines.flow.Flow] cannot be mistaken for a dropped connection. Every test
 * here wraps collection in [withTimeout] with no `.take(...)`: if the server flow failed to
 * complete after the terminal emission (the exact off-by-one this migration guards against), the
 * test would hang until the timeout fires, not pass by accident.
 */
class RegistrationStatusRpcTest :
    FunSpec({

        test("pending registration emits Pending then Approved and COMPLETES when the admin approves") {
            // The MC headline proof, migrated from the retired SSE route's RegistrationApproveE2ETest:
            // the broadcaster ↔ RPC watch ↔ decidePendingRegistration triangle proven end to end with
            // zero mocks, PLUS the applicant can log in immediately once the watch reports approved.
            testApplication {
                useIsolatedTestConfig(registrationPolicy = "APPROVAL_QUEUE")
                application { module() }

                val rest = createClient { install(ContentNegotiation) { json(contractJson) } }
                val rootToken = rest.setupRoot()
                val pendingId = rest.registerPending("darlene")
                val service = publicAuthService()

                val statuses = mutableListOf<String>()
                withTimeout(10.seconds) {
                    service.observeRegistrationStatus(pendingId).collect { event ->
                        val data = event.shouldBeInstanceOf<RpcEvent.Data<RegistrationStatusEvent>>()
                        statuses += data.value.status
                        // Fire the admin decision once we've observed the live "pending" frame — proves
                        // the live broadcast (not just the persisted-status poll) drives the terminal
                        // emission, and that `collect` genuinely returns once it lands.
                        if (data.value.status == "pending") rest.approve(rootToken, pendingId)
                    }
                }

                statuses shouldBe listOf("pending", "approved")

                // The now-ACTIVE applicant can log in with no further ceremony.
                service
                    .login(LoginRequest(email = "darlene@x", password = "y".repeat(8)))
                    .shouldBeInstanceOf<AppResult.Success<AuthSession>>()
            }
        }

        test("already-decided registration emits the terminal status once and completes immediately") {
            testApplication {
                useIsolatedTestConfig(registrationPolicy = "APPROVAL_QUEUE")
                application { module() }

                val rest = createClient { install(ContentNegotiation) { json(contractJson) } }
                val rootToken = rest.setupRoot()
                val pendingId = rest.registerPending("darlene")
                // Decide BEFORE subscribing — no SSE/RPC connection is live when the admin approves.
                rest.approve(rootToken, pendingId)
                val service = publicAuthService()

                val events =
                    withTimeout(10.seconds) {
                        service.observeRegistrationStatus(pendingId).toList()
                    }

                events shouldBe listOf(RpcEvent.Data(RegistrationStatusEvent(status = "approved")))
            }
        }

        test("unknown registration id fails with a typed AppError, not a hang") {
            testApplication {
                useIsolatedTestConfig(registrationPolicy = "APPROVAL_QUEUE")
                application { module() }

                val service = publicAuthService()

                val events =
                    withTimeout(10.seconds) {
                        service.observeRegistrationStatus("does-not-exist").toList()
                    }

                val error = events.single().shouldBeInstanceOf<RpcEvent.Error>()
                error.error.shouldBeInstanceOf<AuthError.RegistrationNotFound>()
            }
        }
    })

/** Opens an unauthenticated [AuthServicePublic] proxy against the harness's in-process public RPC mount. */
private suspend fun ApplicationTestBuilder.publicAuthService(): AuthServicePublic {
    val rpcClient =
        createClient {
            install(WebSockets)
            installKrpc()
        }
    return rpcClient
        .rpc("ws://localhost/api/rpc/public") {
            rpcConfig { serialization { json(contractJson) } }
        }.withService<AuthServicePublic>()
}

/** Runs first-user setup; returns the ROOT (admin) access token. */
private suspend fun HttpClient.setupRoot(): String =
    post("/api/v1/auth/setup") {
        contentType(ContentType.Application.Json)
        setBody(RegisterRequest("root@x", "x".repeat(8), "Root"))
    }.body<AppResult<AuthSession>>()
        .shouldBeInstanceOf<AppResult.Success<AuthSession>>()
        .data
        .accessToken
        .value

/** Registers under APPROVAL_QUEUE; returns the server-issued PENDING_APPROVAL user id. */
private suspend fun HttpClient.registerPending(name: String): String =
    post("/api/v1/auth/register") {
        contentType(ContentType.Application.Json)
        setBody(RegisterRequest("$name@x", "y".repeat(8), name))
    }.body<AppResult<RegisterResult>>()
        .shouldBeInstanceOf<AppResult.Success<RegisterResult>>()
        .data
        .shouldBeInstanceOf<RegisterResult.PendingApproval>()
        .userId
        .value

/** Admin-approves the pending registration [userId]. */
private suspend fun HttpClient.approve(
    rootToken: String,
    userId: String,
) {
    post("/api/v1/admin/users/pending-decision") {
        bearerAuth(rootToken)
        contentType(ContentType.Application.Json)
        setBody("""{"userId":"$userId","approved":true}""")
    }
}
