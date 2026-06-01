package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.PendingRegistrationOutcome
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.dto.auth.User
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication

/**
 * End-to-end coverage for [com.calypsan.listenup.api.AdminUserService]'s REST surface
 * (`adminUserRoutes`): the admin gate lives inside the service, so these tests prove the
 * routes scope to the caller's principal and translate the typed outcome to HTTP — and
 * that a member is rejected with 403 while an admin (the first/ROOT user) succeeds.
 *
 * The decidePendingRegistration case reinstates the approve→login coverage the deleted
 * `AuthRoutesDecidePendingTest` carried: an admin approving a PENDING_APPROVAL applicant
 * lets that applicant log in afterward.
 */
class AdminUserRoutesTest :
    FunSpec({
        test("GET /api/v1/admin/users is 403 for a member, 200 for an admin") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }
                val client = jsonClient()
                val root = client.runSetup()
                val member = client.registerMember("member")

                client.get("/api/v1/admin/users") { bearerAuth(member.token) }.status shouldBe
                    HttpStatusCode.Forbidden
                client.get("/api/v1/admin/users") { bearerAuth(root.token) }.status shouldBe
                    HttpStatusCode.OK
            }
        }

        test("PATCH /api/v1/admin/users/{id} updates role for admin and reflects it in the body") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }
                val client = jsonClient()
                val root = client.runSetup()
                val member = client.registerMember("member")

                val resp =
                    client.patch("/api/v1/admin/users/${member.userId}") {
                        bearerAuth(root.token)
                        contentType(ContentType.Application.Json)
                        setBody("""{"role":"ADMIN"}""")
                    }
                resp.status shouldBe HttpStatusCode.OK
                resp.body<User>().role.name shouldBe "ADMIN"
            }
        }

        test("DELETE self is 409 Conflict; admin deletes a member with 204") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }
                val client = jsonClient()
                val root = client.runSetup()
                val member = client.registerMember("member")

                client.delete("/api/v1/admin/users/${root.userId}") { bearerAuth(root.token) }.status shouldBe
                    HttpStatusCode.Conflict
                client.delete("/api/v1/admin/users/${member.userId}") { bearerAuth(root.token) }.status shouldBe
                    HttpStatusCode.NoContent
            }
        }

        test("GET/PUT /api/v1/admin/settings/registration round-trips the policy") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }
                val client = jsonClient()
                val root = client.runSetup()

                client
                    .get("/api/v1/admin/settings/registration") { bearerAuth(root.token) }
                    .body<RegistrationPolicy>() shouldBe RegistrationPolicy.OPEN

                client
                    .put("/api/v1/admin/settings/registration") {
                        bearerAuth(root.token)
                        contentType(ContentType.Application.Json)
                        setBody(contractJson.encodeToString(RegistrationPolicy.serializer(), RegistrationPolicy.CLOSED))
                    }.status shouldBe HttpStatusCode.OK

                client
                    .get("/api/v1/admin/settings/registration") { bearerAuth(root.token) }
                    .body<RegistrationPolicy>() shouldBe RegistrationPolicy.CLOSED
            }
        }

        test("POST /api/v1/admin/users/pending-decision is 403 for a member") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }
                val client = jsonClient()
                client.runSetup()
                val member = client.registerMember("member")

                client
                    .post("/api/v1/admin/users/pending-decision") {
                        bearerAuth(member.token)
                        contentType(ContentType.Application.Json)
                        setBody("""{"userId":"nobody","approved":true}""")
                    }.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("admin approves a pending registration, then that user can log in") {
            testApplication {
                useIsolatedTestConfig(registrationPolicy = "APPROVAL_QUEUE")
                application { module() }
                val client = jsonClient()
                val root = client.runSetup()

                // Register under APPROVAL_QUEUE → PENDING_APPROVAL, no session yet.
                val pendingId = client.registerPending("pending")

                // An admin approves and gets the typed outcome back.
                val approve =
                    client.post("/api/v1/admin/users/pending-decision") {
                        bearerAuth(root.token)
                        contentType(ContentType.Application.Json)
                        setBody("""{"userId":"$pendingId","approved":true}""")
                    }
                approve.status shouldBe HttpStatusCode.OK
                // The polymorphic discriminator must survive the wire: a denial and an
                // approval are otherwise byte-identical empty objects.
                approve.body<PendingRegistrationOutcome>() shouldBe PendingRegistrationOutcome.Approved

                // The approved applicant's next login succeeds — no extra ceremony.
                client
                    .post("/api/v1/auth/login") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"email":"pending@x","password":"yyyyyyyy"}""")
                    }.status shouldBe HttpStatusCode.OK
            }
        }
    })

/** A registered principal: bearer token + server-issued user id. */
private data class TestUser(
    val token: String,
    val userId: String,
)

private fun ApplicationTestBuilder.jsonClient(): HttpClient =
    createClient {
        install(ContentNegotiation) { json(contractJson) }
    }

/** Runs first-user setup; returns the ROOT (admin) token + id. */
private suspend fun HttpClient.runSetup(): TestUser {
    val session =
        post("/api/v1/auth/setup") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest("root@x", "x".repeat(8), "Root"))
        }.body<AppResult<AuthSession>>()
            .let { it as AppResult.Success<AuthSession> }
            .data
    return TestUser(token = session.accessToken.value, userId = session.user.id.value)
}

/** Registers a MEMBER (OPEN policy); returns token + id. */
private suspend fun HttpClient.registerMember(name: String): TestUser {
    val session =
        post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest("$name@x", "y".repeat(8), name))
        }.body<AppResult<RegisterResult>>()
            .let { it as AppResult.Success<RegisterResult> }
            .data
            .let { it as RegisterResult.Authenticated }
            .session
    return TestUser(token = session.accessToken.value, userId = session.user.id.value)
}

/** Registers under APPROVAL_QUEUE; returns the server-issued PENDING_APPROVAL user id. */
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
