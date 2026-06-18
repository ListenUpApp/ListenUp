package com.calypsan.listenup.web.routes

import com.calypsan.listenup.api.dto.ServerInfo
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.web.testing.csrfToken
import com.calypsan.listenup.web.testing.installTestWebUi
import com.calypsan.listenup.web.testing.sampleAuthSession
import com.calypsan.listenup.web.testing.webClient
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.server.testing.testApplication

private fun serverInfo(policy: RegistrationPolicy) =
    ServerInfo(
        name = "ListenUp", version = "0.0.1", apiVersion = "v1",
        setupRequired = false, registrationPolicy = policy, instanceId = "i1",
    )

class RegisterRoutesTest :
    FunSpec({
        test("GET /register renders the form and sets a CSRF cookie") {
            testApplication {
                installTestWebUi()
                val response = webClient().get("/register")
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "name=\"displayName\""
            }
        }

        test("GET /register under CLOSED policy shows a closed notice, not the form") {
            testApplication {
                val ctx = installTestWebUi()
                ctx.fake.serverInfoResult = AppResult.Success(serverInfo(RegistrationPolicy.CLOSED))
                val response = webClient().get("/register")
                response.status shouldBe HttpStatusCode.OK
                val html = response.bodyAsText()
                html shouldContain "Registration closed"
                html shouldNotContain "name=\"displayName\""
            }
        }

        test("POST /register Authenticated HX-Redirects home") {
            testApplication {
                val ctx = installTestWebUi()
                ctx.fake.registerResult = AppResult.Success(RegisterResult.Authenticated(sampleAuthSession()))
                val client = webClient()
                client.get("/register")
                val token = client.csrfToken()
                val response =
                    client.submitForm(
                        url = "/register",
                        formParameters = parameters {
                            append("email", "a@x"); append("displayName", "A"); append("password", "password1")
                        },
                    ) { header("X-CSRF-Token", token) }
                response.headers["HX-Redirect"] shouldBe "/"
            }
        }

        test("POST /register PendingApproval HX-Redirects to /pending with the user id") {
            testApplication {
                val ctx = installTestWebUi()
                ctx.fake.registerResult = AppResult.Success(RegisterResult.PendingApproval(UserId("u9")))
                val client = webClient()
                client.get("/register")
                val token = client.csrfToken()
                val response =
                    client.submitForm(
                        url = "/register",
                        formParameters = parameters {
                            append("email", "a@x"); append("displayName", "A"); append("password", "password1")
                        },
                    ) { header("X-CSRF-Token", token) }
                response.headers["HX-Redirect"] shouldBe "/pending?userId=u9"
            }
        }

        test("POST /register loopback failure re-renders the form with the typed error") {
            testApplication {
                val ctx = installTestWebUi()
                ctx.fake.registerResult = AppResult.Failure(AuthError.EmailAlreadyExists())
                val client = webClient()
                client.get("/register")
                val token = client.csrfToken()
                val response =
                    client.submitForm(
                        url = "/register",
                        formParameters = parameters {
                            append("email", "a@x"); append("displayName", "A"); append("password", "password1")
                        },
                    ) { header("X-CSRF-Token", token) }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "That email is already registered."
            }
        }
    })
