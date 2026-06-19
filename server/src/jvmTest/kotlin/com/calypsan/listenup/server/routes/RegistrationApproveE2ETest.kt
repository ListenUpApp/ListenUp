package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.PendingRegistrationDecision
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.dto.auth.RegistrationStatusEvent
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout

/**
 * The MC headline proof, wired end to end in a single process: a PENDING applicant's
 * registration-status stream observes its own approval, and the applicant can then log in.
 *
 * This is the broadcaster ↔ SSE route ↔ `decidePendingRegistration` triangle proven with
 * zero mocks — real `module()`, real [com.calypsan.listenup.server.auth.RegistrationBroadcaster],
 * real admin route. Where [RegistrationStatusRoutesTest] drives the broadcaster directly to pin
 * the route's wire shape, this drives the *whole* lifecycle through public HTTP surfaces, so a
 * regression in the admin-decision → broadcaster wiring (the part a unit test can't see) fails here.
 *
 * Anti-flake: the stream is collected inside `async { }` while the admin POST fires concurrently
 * in the same `coroutineScope`, mirroring [com.calypsan.listenup.server.sync.BooksSyncFirehoseTest].
 * The server's `onSubscription` emits `pending` the instant this collector is live, so the
 * `replay = 0` broadcaster can't drop the decision in the subscribe gap. The whole collection is
 * bounded by `withTimeout` — never a real sleep — and `take(2)` ends the stream once the terminal
 * `approved` frame arrives, letting the SSE session and the test complete.
 */
class RegistrationApproveE2ETest :
    FunSpec({
        test("a pending applicant's status stream emits pending then approved, then the applicant logs in") {
            testApplication {
                useIsolatedTestConfig(registrationPolicy = "APPROVAL_QUEUE")
                application { module() }
                val client =
                    createClient {
                        install(ContentNegotiation) { json(contractJson) }
                        install(SSE)
                    }

                // First user becomes admin; capture the bearer token for the decision call.
                val adminToken = client.runSetup()

                // Second registration under APPROVAL_QUEUE → PENDING, no session yet.
                val pendingUserId = client.registerPending("pending")

                // Open the UNAUTHENTICATED status stream for that applicant and observe the
                // lifecycle while the admin approves concurrently. The SSE session lambda returns
                // Unit, so the observed statuses are captured into a var the assertion reads after.
                var statuses: List<String> = emptyList()
                client.sse("/api/v1/auth/registration-status/$pendingUserId/stream") {
                    coroutineScope {
                        val observed =
                            async {
                                withTimeout(10_000) {
                                    incoming
                                        // Heartbeat comments carry null data; keep only status frames.
                                        .filter { it.data != null }
                                        .map { contractJson.decodeFromString<RegistrationStatusEvent>(it.data!!).status }
                                        .take(2)
                                        .toList()
                                }
                            }

                        // Admin approves through the real pending-decision route.
                        client
                            .post("/api/v1/admin/users/pending-decision") {
                                bearerAuth(adminToken)
                                contentType(ContentType.Application.Json)
                                setBody(PendingRegistrationDecision(userId = UserId(pendingUserId), approved = true))
                            }.status shouldBe HttpStatusCode.OK

                        statuses = observed.await()
                    }
                }

                statuses shouldBe listOf("pending", "approved")

                // The now-ACTIVE applicant can log in with no further ceremony.
                client
                    .post("/api/v1/auth/login") {
                        contentType(ContentType.Application.Json)
                        setBody(LoginRequest(email = "pending@x", password = "y".repeat(8)))
                    }.status shouldBe HttpStatusCode.OK
            }
        }
    })

/** Runs first-user setup; returns the ROOT (admin) bearer token. */
private suspend fun HttpClient.runSetup(): String =
    post("/api/v1/auth/setup") {
        contentType(ContentType.Application.Json)
        setBody(RegisterRequest("root@x", "x".repeat(8), "Root"))
    }.body<AppResult<AuthSession>>()
        .let { it as AppResult.Success<AuthSession> }
        .data
        .accessToken
        .value

/** Registers under APPROVAL_QUEUE; returns the server-issued PENDING user id. */
private suspend fun HttpClient.registerPending(name: String): String =
    post("/api/v1/auth/register") {
        contentType(ContentType.Application.Json)
        setBody(RegisterRequest("$name@x", "y".repeat(8), name))
    }.body<AppResult<RegisterResult>>()
        .let { it as AppResult.Success<RegisterResult> }
        .data
        .let { it as RegisterResult.PendingApproval }
        .userId
        .value
