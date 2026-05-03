package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication

class AuthRoutesRegisterTest :
    FunSpec({

        suspend fun HttpClient.seedRoot() {
            post("/api/v1/auth/setup") {
                contentType(ContentType.Application.Json)
                setBody(RegisterRequest("root@x", "x".repeat(8), "Root"))
            }
        }

        test("POST /register on empty instance returns SetupRequired (409)") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }
                val client = createClient { install(ContentNegotiation) { json() } }

                val r =
                    client.post("/api/v1/auth/register") {
                        contentType(ContentType.Application.Json)
                        setBody(RegisterRequest("alice@x", "x".repeat(8), "Alice"))
                    }

                r.status shouldBe HttpStatusCode.Conflict
                r
                    .body<AppResult<RegisterResult>>()
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<AuthError.SetupRequired>()
            }
        }

        test("POST /register on OPEN instance returns Authenticated") {
            testApplication {
                useIsolatedTestConfig(registrationPolicy = "OPEN")
                application { module() }
                val client = createClient { install(ContentNegotiation) { json() } }
                client.seedRoot()

                val r =
                    client.post("/api/v1/auth/register") {
                        contentType(ContentType.Application.Json)
                        setBody(RegisterRequest("alice@x", "x".repeat(8), "Alice"))
                    }

                r.status shouldBe HttpStatusCode.OK
                r
                    .body<AppResult<RegisterResult>>()
                    .shouldBeInstanceOf<AppResult.Success<RegisterResult>>()
                    .data
                    .shouldBeInstanceOf<RegisterResult.Authenticated>()
            }
        }

        test("POST /register on APPROVAL_QUEUE returns PendingApproval") {
            testApplication {
                useIsolatedTestConfig(registrationPolicy = "APPROVAL_QUEUE")
                application { module() }
                val client = createClient { install(ContentNegotiation) { json() } }
                client.seedRoot()

                val r =
                    client.post("/api/v1/auth/register") {
                        contentType(ContentType.Application.Json)
                        setBody(RegisterRequest("alice@x", "x".repeat(8), "Alice"))
                    }

                r.status shouldBe HttpStatusCode.OK
                r
                    .body<AppResult<RegisterResult>>()
                    .shouldBeInstanceOf<AppResult.Success<RegisterResult>>()
                    .data shouldBe RegisterResult.PendingApproval
            }
        }

        test("POST /register on CLOSED instance returns RegistrationDisabled (403)") {
            testApplication {
                useIsolatedTestConfig(registrationPolicy = "CLOSED")
                application { module() }
                val client = createClient { install(ContentNegotiation) { json() } }
                client.seedRoot()

                val r =
                    client.post("/api/v1/auth/register") {
                        contentType(ContentType.Application.Json)
                        setBody(RegisterRequest("alice@x", "x".repeat(8), "Alice"))
                    }

                r.status shouldBe HttpStatusCode.Forbidden
                r
                    .body<AppResult<RegisterResult>>()
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<AuthError.RegistrationDisabled>()
            }
        }

        test("POST /register with duplicate email returns EmailAlreadyExists (409)") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }
                val client = createClient { install(ContentNegotiation) { json() } }
                client.seedRoot()
                client.post("/api/v1/auth/register") {
                    contentType(ContentType.Application.Json)
                    setBody(RegisterRequest("alice@x", "x".repeat(8), "Alice"))
                }

                val r =
                    client.post("/api/v1/auth/register") {
                        contentType(ContentType.Application.Json)
                        setBody(RegisterRequest("ALICE@X", "x".repeat(8), "Alice2"))
                    }

                r.status shouldBe HttpStatusCode.Conflict
                r
                    .body<AppResult<RegisterResult>>()
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<AuthError.EmailAlreadyExists>()
            }
        }
    })
