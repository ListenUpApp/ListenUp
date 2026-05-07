package com.calypsan.listenup.server.plugins

import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.error.TransportError
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
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.serialization.kotlinx.json.json as serverJson

/**
 * Pinpoints the StatusPages handler's two responsibilities:
 *  - Unhandled `Throwable` becomes [InternalError(correlationId)] with HTTP 500.
 *  - Unknown paths return a structured 404 JSON body.
 *
 * Domain failures DO NOT surface here — services return [AppResult.Failure]
 * in-band; the route handler folds it. So no AuthException-specific test
 * exists (and `AuthException` itself is gone from the codebase).
 */
class AppErrorStatusPagesTest :
    FunSpec({

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

        test("TransportError maps to InternalServerError as a server-local bug guard") {
            val err: AppError = TransportError.NetworkUnavailable()
            err.toHttpStatus() shouldBe HttpStatusCode.InternalServerError
        }

        test("unknown paths return structured JSON 404") {
            testApplication {
                application {
                    install(ServerContentNegotiation) { serverJson() }
                    install(CallId) { generate { "any" } }
                    installAppErrorStatusPages()
                    routing {
                        get("/exists") { /* default 200 reply */ }
                    }
                }
                val client = createClient { install(ContentNegotiation) { json() } }

                val response = client.get("/this-does-not-exist")
                response.status shouldBe HttpStatusCode.NotFound
            }
        }
    })
