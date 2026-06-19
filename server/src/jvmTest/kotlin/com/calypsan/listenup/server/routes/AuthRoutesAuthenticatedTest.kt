package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.dto.auth.SessionSummary
import com.calypsan.listenup.api.dto.auth.User
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
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
import io.ktor.server.testing.testApplication

/**
 * Wire-layer integration tests for authenticated routes — exercises the
 * JWT bearer auth wall plus the principal-pass into AuthServiceAuthed
 * methods.
 */
class AuthRoutesAuthenticatedTest :
    FunSpec({

        suspend fun HttpClient.seedAndLoginAlice(): AuthSession {
            post("/api/v1/auth/setup") {
                contentType(ContentType.Application.Json)
                setBody(RegisterRequest("root@x", "x".repeat(8), "Root"))
            }
            val first =
                post("/api/v1/auth/register") {
                    contentType(ContentType.Application.Json)
                    setBody(RegisterRequest("alice@x", "x".repeat(8), "Alice"))
                }.body<AppResult<RegisterResult>>()
            return first
                .shouldBeInstanceOf<AppResult.Success<RegisterResult>>()
                .data
                .shouldBeInstanceOf<RegisterResult.Authenticated>()
                .session
        }

        suspend fun HttpClient.loginAlice(): AuthSession =
            post("/api/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(LoginRequest("alice@x", "x".repeat(8)))
            }.body<AppResult<AuthSession>>()
                .shouldBeInstanceOf<AppResult.Success<AuthSession>>()
                .data

        test("GET /current-user with valid bearer returns the User") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }
                val client = createClient { install(ContentNegotiation) { json() } }
                val s = client.seedAndLoginAlice()

                val r = client.get("/api/v1/auth/current-user") { bearerAuth(s.accessToken.value) }

                r.status shouldBe HttpStatusCode.OK
                val user =
                    r
                        .body<AppResult<User>>()
                        .shouldBeInstanceOf<AppResult.Success<User>>()
                        .data
                user.email shouldBe "alice@x"
            }
        }

        test("GET /current-user without bearer returns 401") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }
                val client = createClient { install(ContentNegotiation) { json() } }
                client.seedAndLoginAlice()

                val r = client.get("/api/v1/auth/current-user")

                r.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("POST /logout revokes only the caller's session") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }
                val client = createClient { install(ContentNegotiation) { json() } }
                val a = client.seedAndLoginAlice()
                val b = client.loginAlice()

                client
                    .post("/api/v1/auth/logout") { bearerAuth(a.accessToken.value) }
                    .status shouldBe HttpStatusCode.OK

                client
                    .get("/api/v1/auth/current-user") { bearerAuth(a.accessToken.value) }
                    .status shouldBe HttpStatusCode.Unauthorized
                client
                    .get("/api/v1/auth/current-user") { bearerAuth(b.accessToken.value) }
                    .status shouldBe HttpStatusCode.OK
            }
        }

        test("POST /logout/all revokes every session of the caller") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }
                val client = createClient { install(ContentNegotiation) { json() } }
                val a = client.seedAndLoginAlice()
                val b = client.loginAlice()

                client
                    .post("/api/v1/auth/logout/all") { bearerAuth(a.accessToken.value) }
                    .status shouldBe HttpStatusCode.OK

                client
                    .get("/api/v1/auth/current-user") { bearerAuth(a.accessToken.value) }
                    .status shouldBe HttpStatusCode.Unauthorized
                client
                    .get("/api/v1/auth/current-user") { bearerAuth(b.accessToken.value) }
                    .status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("GET /sessions returns active sessions with one marked current") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }
                val client = createClient { install(ContentNegotiation) { json() } }
                val a = client.seedAndLoginAlice()
                client.loginAlice()

                val r = client.get("/api/v1/auth/sessions") { bearerAuth(a.accessToken.value) }

                r.status shouldBe HttpStatusCode.OK
                val list =
                    r
                        .body<AppResult<List<SessionSummary>>>()
                        .shouldBeInstanceOf<AppResult.Success<List<SessionSummary>>>()
                        .data
                list.size shouldBe 2
                list.count { it.current } shouldBe 1
                list.first { it.current }.id shouldBe a.sessionId
            }
        }
    })
