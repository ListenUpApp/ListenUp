package com.calypsan.listenup.web.routes

import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.web.testing.csrfToken
import com.calypsan.listenup.web.testing.installTestWebUi
import com.calypsan.listenup.web.testing.sampleAuthSession
import com.calypsan.listenup.web.testing.webClient
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.server.testing.testApplication

class LoginRoutesTest :
    FunSpec({
        test("GET /login renders the form and sets a CSRF cookie") {
            testApplication {
                installTestWebUi()
                val response = webClient().get("/login")
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "name=\"email\""
                response.headers.getAll(HttpHeaders.SetCookie).orEmpty().any { it.startsWith("lu_csrf=") } shouldBe true
            }
        }

        test("POST /login with good credentials creates a session and HX-Redirects home") {
            testApplication {
                val ctx = installTestWebUi()
                ctx.fake.loginResult = AppResult.Success(sampleAuthSession())
                val client = webClient()
                client.get("/login")
                val token = client.csrfToken()

                val response =
                    client.submitForm(
                        url = "/login",
                        formParameters = parameters {
                            append("email", "a@x")
                            append("password", "password1")
                        },
                    ) { header("X-CSRF-Token", token) }

                response.headers["HX-Redirect"] shouldBe "/"
                response.headers.getAll(HttpHeaders.SetCookie).orEmpty().any { it.startsWith("lu_session=") } shouldBe true
            }
        }

        test("POST /login with bad credentials re-renders the form with the typed error") {
            testApplication {
                val ctx = installTestWebUi()
                ctx.fake.loginResult = AppResult.Failure(AuthError.InvalidCredentials())
                val client = webClient()
                client.get("/login")
                val token = client.csrfToken()

                // A valid-length password so LoginRequest constructs and the loopback's
                // Failure branch is genuinely exercised (not short-circuited by validation).
                val response =
                    client.submitForm(
                        url = "/login",
                        formParameters = parameters {
                            append("email", "a@x")
                            append("password", "wrongpassword")
                        },
                    ) { header("X-CSRF-Token", token) }

                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "Email or password did not match."
            }
        }

        test("POST /login with a too-short password re-renders the error without reaching the loopback") {
            testApplication {
                val ctx = installTestWebUi()
                // If the loopback were reached it would succeed and HX-Redirect; a too-short
                // password must short-circuit on LoginRequest validation and re-render instead.
                ctx.fake.loginResult = AppResult.Success(sampleAuthSession())
                val client = webClient()
                client.get("/login")
                val token = client.csrfToken()

                val response =
                    client.submitForm(
                        url = "/login",
                        formParameters = parameters {
                            append("email", "a@x")
                            append("password", "short")
                        },
                    ) { header("X-CSRF-Token", token) }

                response.headers["HX-Redirect"] shouldBe null
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "Email or password did not match."
            }
        }
    })
