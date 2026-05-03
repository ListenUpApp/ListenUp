package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication

/** Wire-layer integration tests for `POST /api/v1/auth/setup`. */
class AuthRoutesSetupTest :
    FunSpec({

        test("POST /setup on empty instance creates ROOT user and returns AuthSession") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }
                val client = createClient { install(ContentNegotiation) { json() } }

                val r =
                    client.post("/api/v1/auth/setup") {
                        contentType(ContentType.Application.Json)
                        setBody(RegisterRequest("root@x", "x".repeat(8), "Root"))
                    }

                r.status shouldBe HttpStatusCode.OK
                val body = r.body<AuthSession>()
                body.user.role shouldBe UserRole.ROOT
                body.user.email shouldBe "root@x"
            }
        }

        test("POST /setup on populated instance returns SetupAlreadyComplete (409)") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }
                val client = createClient { install(ContentNegotiation) { json() } }
                client.post("/api/v1/auth/setup") {
                    contentType(ContentType.Application.Json)
                    setBody(RegisterRequest("root@x", "x".repeat(8), "Root"))
                }

                val r =
                    client.post("/api/v1/auth/setup") {
                        contentType(ContentType.Application.Json)
                        setBody(RegisterRequest("other@x", "x".repeat(8), "Other"))
                    }

                r.status shouldBe HttpStatusCode.Conflict
                r.body<AppError>().shouldBeInstanceOf<AuthError.SetupAlreadyComplete>()
            }
        }
    })
