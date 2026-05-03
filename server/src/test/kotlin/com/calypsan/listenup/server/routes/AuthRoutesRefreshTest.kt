package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.RefreshRequest
import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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

/** Wire-layer integration tests for `POST /api/v1/auth/refresh`. */
class AuthRoutesRefreshTest :
    FunSpec({

        suspend fun HttpClient.seedRootAndRegisterAlice(): AuthSession {
            post("/api/v1/auth/setup") {
                contentType(ContentType.Application.Json)
                setBody(RegisterRequest("root@x", "x".repeat(8), "Root"))
            }
            val result =
                post("/api/v1/auth/register") {
                    contentType(ContentType.Application.Json)
                    setBody(RegisterRequest("alice@x", "x".repeat(8), "Alice"))
                }.body<RegisterResult>()
            return result.shouldBeInstanceOf<RegisterResult.Authenticated>().session
        }

        test("POST /refresh rotates the refresh token, returns new pair tied to same session") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }
                val client = createClient { install(ContentNegotiation) { json() } }
                val initial = client.seedRootAndRegisterAlice()

                val r =
                    client.post("/api/v1/auth/refresh") {
                        contentType(ContentType.Application.Json)
                        setBody(RefreshRequest(initial.refreshToken))
                    }

                r.status shouldBe HttpStatusCode.OK
                val rotated = r.body<AuthSession>()
                rotated.sessionId shouldBe initial.sessionId
                rotated.refreshToken.value shouldNotBe initial.refreshToken.value
            }
        }

        test("POST /refresh on an unknown token returns InvalidRefreshToken with familyRevoked=false") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }
                val client = createClient { install(ContentNegotiation) { json() } }
                client.seedRootAndRegisterAlice()

                val r =
                    client.post("/api/v1/auth/refresh") {
                        contentType(ContentType.Application.Json)
                        setBody(RefreshRequest(RefreshToken("never-issued")))
                    }

                r.status shouldBe HttpStatusCode.Unauthorized
                val err = r.body<AppError>().shouldBeInstanceOf<AuthError.InvalidRefreshToken>()
                err.familyRevoked shouldBe false
            }
        }

        test("POST /refresh on a replayed token returns InvalidRefreshToken with familyRevoked=true") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }
                val client = createClient { install(ContentNegotiation) { json() } }
                val initial = client.seedRootAndRegisterAlice()

                client.post("/api/v1/auth/refresh") {
                    contentType(ContentType.Application.Json)
                    setBody(RefreshRequest(initial.refreshToken))
                }

                val r =
                    client.post("/api/v1/auth/refresh") {
                        contentType(ContentType.Application.Json)
                        setBody(RefreshRequest(initial.refreshToken))
                    }

                r.status shouldBe HttpStatusCode.Unauthorized
                val err = r.body<AppError>().shouldBeInstanceOf<AuthError.InvalidRefreshToken>()
                err.familyRevoked shouldBe true
            }
        }
    })
