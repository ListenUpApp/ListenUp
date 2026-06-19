package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.dto.invite.InviteDto
import com.calypsan.listenup.api.dto.invite.InvitePreview
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication

/**
 * End-to-end coverage for the invite REST surface — both the admin-gated lifecycle
 * (`adminInviteRoutes`, mounted inside the JWT wall) and the anonymous landing-page /
 * claim surface (`publicInviteRoutes`, mounted outside it).
 *
 * Proves three things the wiring must guarantee:
 *  - `POST /api/v1/admin/invites` rejects a member (403) and accepts an admin (200);
 *  - `GET /api/v1/invites/{code}` returns a preview with NO bearer token attached;
 *  - `POST /api/v1/invites/{code}/claim` mints an ACTIVE account whose owner can then
 *    log in — the reinstated end-to-end admission path.
 */
class InviteRoutesTest :
    FunSpec({
        test("POST /api/v1/admin/invites is 403 for a member, 200 for an admin") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }
                val client = jsonClient()
                val root = client.runSetup()
                val member = client.registerMember("member")

                client
                    .post("/api/v1/admin/invites") {
                        bearerAuth(member.token)
                        contentType(ContentType.Application.Json)
                        setBody("""{"email":"pal@x.y","displayName":"Pal","role":"MEMBER"}""")
                    }.status shouldBe HttpStatusCode.Forbidden

                client
                    .post("/api/v1/admin/invites") {
                        bearerAuth(root.token)
                        contentType(ContentType.Application.Json)
                        setBody("""{"email":"pal@x.y","displayName":"Pal","role":"MEMBER"}""")
                    }.status shouldBe HttpStatusCode.OK
            }
        }

        test("GET /api/v1/invites/{code} returns a preview for a valid code (no auth required)") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }
                val client = jsonClient()
                val root = client.runSetup()

                val invite =
                    client
                        .post("/api/v1/admin/invites") {
                            bearerAuth(root.token)
                            contentType(ContentType.Application.Json)
                            setBody("""{"email":"pal@x.y","displayName":"Pal","role":"MEMBER"}""")
                        }.body<InviteDto>()

                // UNAUTHENTICATED lookup — no bearer token.
                val preview =
                    client
                        .get("/api/v1/invites/${invite.code}")
                        .also { it.status shouldBe HttpStatusCode.OK }
                        .body<InvitePreview>()

                preview.email shouldBe "pal@x.y"
                preview.valid shouldBe true
            }
        }

        test("POST /api/v1/invites/{code}/claim creates an account and the claimer can then log in") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }
                val client = jsonClient()
                val root = client.runSetup()

                val invite =
                    client
                        .post("/api/v1/admin/invites") {
                            bearerAuth(root.token)
                            contentType(ContentType.Application.Json)
                            setBody("""{"email":"newpal@x.y","displayName":"New Pal","role":"MEMBER"}""")
                        }.body<InviteDto>()

                // UNAUTHENTICATED claim — issues a session and creates the ACTIVE account.
                client
                    .post("/api/v1/invites/${invite.code}/claim") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"password":"zzzzzzzz"}""")
                    }.status shouldBe HttpStatusCode.OK

                // The reinstated end-to-end coverage: the claimer can now log in.
                client
                    .post("/api/v1/auth/login") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"email":"newpal@x.y","password":"zzzzzzzz"}""")
                    }.status shouldBe HttpStatusCode.OK
            }
        }
    })

/** A registered principal: bearer token + server-issued user id. */
private data class InviteTestUser(
    val token: String,
    val userId: String,
)

private fun ApplicationTestBuilder.jsonClient(): HttpClient =
    createClient {
        install(ContentNegotiation) { json(contractJson) }
    }

/** Runs first-user setup; returns the ROOT (admin) token + id. */
private suspend fun HttpClient.runSetup(): InviteTestUser {
    val session =
        post("/api/v1/auth/setup") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest("root@x", "x".repeat(8), "Root"))
        }.body<AppResult<AuthSession>>()
            .let { it as AppResult.Success<AuthSession> }
            .data
    return InviteTestUser(token = session.accessToken.value, userId = session.user.id.value)
}

/** Registers a MEMBER (OPEN policy); returns token + id. */
private suspend fun HttpClient.registerMember(name: String): InviteTestUser {
    val session =
        post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest("$name@x", "y".repeat(8), name))
        }.body<AppResult<RegisterResult>>()
            .let { it as AppResult.Success<RegisterResult> }
            .data
            .let { it as RegisterResult.Authenticated }
            .session
    return InviteTestUser(token = session.accessToken.value, userId = session.user.id.value)
}
