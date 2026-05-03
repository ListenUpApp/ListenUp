package com.calypsan.listenup.server.plugins

import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.server.auth.AuthException
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.serialization.kotlinx.json.json as serverJson
import java.util.UUID

/**
 * Pinpoint test for the StatusPages handler — proves AuthException unwraps to a
 * typed AppError on the wire and that unhandled Throwable becomes InternalError.
 */
class AppErrorStatusPagesTest :
    FunSpec({

        test("AuthException unwraps to typed AuthError JSON with mapped HTTP status") {
            testApplication {
                application {
                    install(ServerContentNegotiation) { serverJson() }
                    install(CallId) { generate { UUID.randomUUID().toString() } }
                    installAppErrorStatusPages()
                    routing {
                        get("/throws-invalid-credentials") {
                            throw AuthException(AuthError.InvalidCredentials())
                        }
                        get("/throws-conflict") {
                            throw AuthException(AuthError.EmailAlreadyExists())
                        }
                    }
                }
                val client =
                    createClient {
                        install(ContentNegotiation) { json() }
                    }

                val unauthorized = client.get("/throws-invalid-credentials")
                unauthorized.status shouldBe HttpStatusCode.Unauthorized
                unauthorized.body<AppError>().shouldBeInstanceOf<AuthError.InvalidCredentials>()

                val conflict = client.get("/throws-conflict")
                conflict.status shouldBe HttpStatusCode.Conflict
                conflict.body<AppError>().shouldBeInstanceOf<AuthError.EmailAlreadyExists>()
            }
        }

        test("unhandled Throwable becomes InternalError(correlationId) with HTTP 500") {
            testApplication {
                application {
                    install(ServerContentNegotiation) { serverJson() }
                    install(CallId) { generate { "test-correlation-id" } }
                    installAppErrorStatusPages()
                    routing {
                        get("/throws-bug") { error("simulated bug") }
                    }
                }
                val client =
                    createClient {
                        install(ContentNegotiation) { json() }
                    }

                val response = client.get("/throws-bug")
                response.status shouldBe HttpStatusCode.InternalServerError
                val body = response.body<AppError>()
                val internal = body.shouldBeInstanceOf<InternalError>()
                internal.correlationId shouldBe "test-correlation-id"
            }
        }

        test("AuthError carries the request's correlation id") {
            testApplication {
                application {
                    install(ServerContentNegotiation) { serverJson() }
                    install(CallId) { generate { "fixed-corr-id" } }
                    installAppErrorStatusPages()
                    routing {
                        get("/needs-corr") {
                            throw AuthException(AuthError.PermissionDenied())
                        }
                    }
                }
                val client =
                    createClient {
                        install(ContentNegotiation) { json() }
                    }

                val response = client.get("/needs-corr")
                response.status shouldBe HttpStatusCode.Forbidden
                val typed = response.body<AppError>().shouldBeInstanceOf<AuthError.PermissionDenied>()
                typed.correlationId shouldBe "fixed-corr-id"
            }
        }
    })
