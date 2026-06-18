package com.calypsan.listenup.web.routes

import com.calypsan.listenup.api.dto.auth.RegistrationStatusEvent
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.web.testing.installTestWebUi
import com.calypsan.listenup.web.testing.webClient
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication

class PendingRoutesTest :
    FunSpec({
        test("GET /pending renders the SSE subscription and poll fallback for the user id") {
            testApplication {
                installTestWebUi()
                val response = webClient().get("/pending?userId=u9")
                response.status shouldBe HttpStatusCode.OK
                val html = response.bodyAsText()
                html shouldContain "/api/v1/auth/registration-status/u9/stream"
                html shouldContain "/pending/status?userId=u9"
            }
        }

        test("GET /pending/status approved HX-Redirects to /login") {
            testApplication {
                val ctx = installTestWebUi()
                ctx.fake.registrationStatusResult = AppResult.Success(RegistrationStatusEvent(status = "approved"))
                val response = webClient().get("/pending/status?userId=u9")
                response.headers["HX-Redirect"] shouldBe "/login"
            }
        }

        test("GET /pending/status denied shows the reason") {
            testApplication {
                val ctx = installTestWebUi()
                ctx.fake.registrationStatusResult =
                    AppResult.Success(RegistrationStatusEvent(status = "denied", message = "No room at the inn."))
                val response = webClient().get("/pending/status?userId=u9")
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "No room at the inn."
            }
        }

        test("GET /pending/status pending shows the waiting message") {
            testApplication {
                val ctx = installTestWebUi()
                ctx.fake.registrationStatusResult = AppResult.Success(RegistrationStatusEvent(status = "pending"))
                val response = webClient().get("/pending/status?userId=u9")
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "awaiting"
            }
        }

        test("GET /pending/status falls back to the waiting message on a loopback failure") {
            testApplication {
                val ctx = installTestWebUi()
                // Never-Stranded: a transient loopback failure must look like "still waiting",
                // not a dead-end error — the next poll retries.
                ctx.fake.registrationStatusResult = AppResult.Failure(InternalError(debugInfo = "boom"))
                val response = webClient().get("/pending/status?userId=u9")
                response.status shouldBe HttpStatusCode.OK
                response.headers["HX-Redirect"] shouldBe null
                response.bodyAsText() shouldContain "awaiting"
            }
        }
    })
