package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.dto.auth.RegistrationStatusEvent
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.auth.RegistrationBroadcaster
import com.calypsan.listenup.server.auth.RegistrationDecision
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import org.koin.ktor.ext.inject

/**
 * Pins the multi-user MC §Task-3 contract: the registration-status SSE stream is mounted
 * OUTSIDE the JWT wall (a registrant awaiting approval has no token yet) and emits the exact
 * client wire shape — a data-only `RegistrationStatusEvent` JSON frame whose `status` reflects
 * the registrant's persisted state, then the broadcaster-driven terminal decision.
 *
 * The connection is established with NO `bearerAuth`; a 401 here would mean the route was
 * accidentally placed inside `authenticate(JWT_PROVIDER)` and is a regression.
 */
class RegistrationStatusRoutesTest :
    FunSpec({

        test("registration-status stream emits pending then the live decision, unauthenticated") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }
                val broadcaster by application.inject<RegistrationBroadcaster>()
                val client =
                    createClient {
                        install(SSE)
                    }
                client.sse("/api/v1/auth/registration-status/u1/stream") {
                    // Heartbeat comment-events carry null data; filter to the real status frames.
                    // The first data frame is "pending" (u1 has no persisted row); firing the
                    // decision then yields the terminal "approved" frame and the server closes.
                    val statuses =
                        incoming
                            .filter { it.data != null }
                            .map { contractJson.decodeFromString<RegistrationStatusEvent>(it.data!!).status }
                            .onEach { status ->
                                if (status == "pending") broadcaster.notify("u1", RegistrationDecision.Approved)
                            }.take(2)
                            .toList()

                    statuses shouldBe listOf("pending", "approved")
                }
            }
        }

        test("one-shot GET registration-status reports the persisted decision (pull fallback)") {
            // The "never stranded" pull path: a client whose SSE stream never delivers (e.g. iOS
            // Darwin) can still learn it was approved via a plain request/response GET.
            testApplication {
                useIsolatedTestConfig(registrationPolicy = "APPROVAL_QUEUE")
                application { module() }
                val rest = createClient { install(ContentNegotiation) { json(contractJson) } }
                val rootToken = rest.setupRoot()
                val pendingId = rest.registerPending("darlene")

                rest
                    .get("/api/v1/auth/registration-status/$pendingId")
                    .body<RegistrationStatusEvent>()
                    .status shouldBe "pending"

                rest.approve(rootToken, pendingId)

                rest
                    .get("/api/v1/auth/registration-status/$pendingId")
                    .body<RegistrationStatusEvent>()
                    .status shouldBe "approved"
            }
        }

        test("registration-status stream reports approved on connect when the decision was already made") {
            // Regression: the registrant's SSE wasn't live at the instant the admin
            // approved (replay=0 broadcaster → the live push was dropped). On a fresh connect
            // / reconnect / "check status", the stream must report the PERSISTED status, not an
            // eternal "pending". Otherwise the iOS Awaiting screen never advances.
            testApplication {
                useIsolatedTestConfig(registrationPolicy = "APPROVAL_QUEUE")
                application { module() }
                val rest = createClient { install(ContentNegotiation) { json(contractJson) } }
                val rootToken = rest.setupRoot()
                val pendingId = rest.registerPending("darlene")
                // Approve while NO SSE is connected — the live broadcast is dropped.
                rest.approve(rootToken, pendingId)

                val sse = createClient { install(SSE) }
                sse.sse("/api/v1/auth/registration-status/$pendingId/stream") {
                    val firstStatus =
                        incoming
                            .filter { it.data != null }
                            .map { contractJson.decodeFromString<RegistrationStatusEvent>(it.data!!).status }
                            .first()
                    firstStatus shouldBe "approved"
                }
            }
        }
    })

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
