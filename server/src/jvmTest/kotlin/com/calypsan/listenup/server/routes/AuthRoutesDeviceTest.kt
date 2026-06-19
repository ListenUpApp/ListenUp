package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.DeviceInfo
import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.SessionSummary
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
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication

/**
 * Wire-layer integration tests for the device-metadata surface: the public
 * auth routes capture the `User-Agent` header and the `DeviceInfo` body onto
 * the minted session, and `DELETE /api/v1/auth/sessions/{id}` revokes a
 * session the caller owns.
 */
class AuthRoutesDeviceTest :
    FunSpec({

        suspend fun HttpClient.seedRoot() {
            post("/api/v1/auth/setup") {
                contentType(ContentType.Application.Json)
                setBody(RegisterRequest("root@x", "x".repeat(8), "Root"))
            }
        }

        suspend fun HttpClient.sessionsOf(accessToken: String): List<SessionSummary> =
            get("/api/v1/auth/sessions") { bearerAuth(accessToken) }
                .body<AppResult<List<SessionSummary>>>()
                .shouldBeInstanceOf<AppResult.Success<List<SessionSummary>>>()
                .data

        test("login forwards User-Agent header onto the session, visible via GET sessions") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }
                val client = createClient { install(ContentNegotiation) { json() } }
                client.seedRoot()

                val session =
                    client
                        .post("/api/v1/auth/login") {
                            contentType(ContentType.Application.Json)
                            header(HttpHeaders.UserAgent, "ListenUp-Test/1.0")
                            setBody(LoginRequest("root@x", "x".repeat(8)))
                        }.body<AppResult<AuthSession>>()
                        .shouldBeInstanceOf<AppResult.Success<AuthSession>>()
                        .data

                val sessions = client.sessionsOf(session.accessToken.value)
                sessions.first { it.current }.userAgent shouldBe "ListenUp-Test/1.0"
            }
        }

        test("login forwards DeviceInfo body onto the session") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }
                val client = createClient { install(ContentNegotiation) { json() } }
                client.seedRoot()

                val session =
                    client
                        .post("/api/v1/auth/login") {
                            contentType(ContentType.Application.Json)
                            setBody(
                                LoginRequest(
                                    email = "root@x",
                                    password = "x".repeat(8),
                                    deviceInfo = DeviceInfo(deviceModel = "Pixel 10", platform = "Android"),
                                ),
                            )
                        }.body<AppResult<AuthSession>>()
                        .shouldBeInstanceOf<AppResult.Success<AuthSession>>()
                        .data

                val sessions = client.sessionsOf(session.accessToken.value)
                sessions.first { it.current }.deviceInfo?.deviceModel shouldBe "Pixel 10"
            }
        }

        test("DELETE /api/v1/auth/sessions/{id} revokes a session") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }
                val client = createClient { install(ContentNegotiation) { json() } }
                client.seedRoot()

                val a =
                    client
                        .post("/api/v1/auth/login") {
                            contentType(ContentType.Application.Json)
                            setBody(LoginRequest("root@x", "x".repeat(8)))
                        }.body<AppResult<AuthSession>>()
                        .shouldBeInstanceOf<AppResult.Success<AuthSession>>()
                        .data
                val b =
                    client
                        .post("/api/v1/auth/login") {
                            contentType(ContentType.Application.Json)
                            setBody(LoginRequest("root@x", "x".repeat(8)))
                        }.body<AppResult<AuthSession>>()
                        .shouldBeInstanceOf<AppResult.Success<AuthSession>>()
                        .data

                val before = client.sessionsOf(a.accessToken.value)
                before.any { it.id == b.sessionId } shouldBe true

                client
                    .delete("/api/v1/auth/sessions/${b.sessionId.value}") { bearerAuth(a.accessToken.value) }
                    .status shouldBe HttpStatusCode.OK

                val remaining = client.sessionsOf(a.accessToken.value)
                remaining.size shouldBe before.size - 1
                remaining.none { it.id == b.sessionId } shouldBe true
            }
        }
    })
