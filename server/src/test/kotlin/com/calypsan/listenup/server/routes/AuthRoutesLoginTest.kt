package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
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

/**
 * Wire-layer integration tests for `POST /api/v1/auth/login` — exercise the
 * real `Application.module()` so route + plugin + StatusPages + Koin wiring
 * is all in scope. Pre-seeding happens through the REST surface itself.
 */
class AuthRoutesLoginTest :
    FunSpec({

        suspend fun HttpClient.seedRootAndAlice() {
            post("/api/v1/auth/setup") {
                contentType(ContentType.Application.Json)
                setBody(RegisterRequest("root@x", "x".repeat(8), "Root"))
            }
            post("/api/v1/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(RegisterRequest("alice@x", "correctpassword", "Alice"))
            }
        }

        test("POST /api/v1/auth/login returns AuthSession for valid credentials") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }
                val client = createClient { install(ContentNegotiation) { json() } }
                client.seedRootAndAlice()

                val response =
                    client.post("/api/v1/auth/login") {
                        contentType(ContentType.Application.Json)
                        setBody(LoginRequest("alice@x", "correctpassword"))
                    }

                response.status shouldBe HttpStatusCode.OK
                val body = response.body<AuthSession>()
                body.user.email shouldBe "alice@x"
                body.accessToken.value.shouldNotBeBlank()
            }
        }

        test("POST /api/v1/auth/login returns 401 + AuthError.InvalidCredentials on wrong password") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }
                val client = createClient { install(ContentNegotiation) { json() } }
                client.seedRootAndAlice()

                val response =
                    client.post("/api/v1/auth/login") {
                        contentType(ContentType.Application.Json)
                        setBody(LoginRequest("alice@x", "WRONG-PASSWORD"))
                    }

                response.status shouldBe HttpStatusCode.Unauthorized
                val err = response.body<AppError>().shouldBeInstanceOf<AuthError.InvalidCredentials>()
                err.correlationId.shouldNotBeBlank()
            }
        }

        test("POST /api/v1/auth/login returns 401 on unknown email") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }
                val client = createClient { install(ContentNegotiation) { json() } }
                client.seedRootAndAlice()

                val response =
                    client.post("/api/v1/auth/login") {
                        contentType(ContentType.Application.Json)
                        setBody(LoginRequest("ghost@x", "x".repeat(8)))
                    }

                response.status shouldBe HttpStatusCode.Unauthorized
                response.body<AppError>().shouldBeInstanceOf<AuthError.InvalidCredentials>()
            }
        }
    })
