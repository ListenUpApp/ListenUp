package com.calypsan.listenup.web.routes

import com.calypsan.listenup.api.dto.ServerInfo
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
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
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.server.testing.testApplication

private fun info(setupRequired: Boolean) =
    ServerInfo(
        name = "ListenUp", version = "0.0.1", apiVersion = "v1",
        setupRequired = setupRequired, registrationPolicy = RegistrationPolicy.OPEN, instanceId = "i1",
    )

class SetupRoutesTest :
    FunSpec({
        test("GET /setup renders the root-setup form when setup is required") {
            testApplication {
                val ctx = installTestWebUi()
                ctx.fake.serverInfoResult = AppResult.Success(info(setupRequired = true))
                val response = webClient().get("/setup")
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "name=\"displayName\""
            }
        }

        test("GET /setup redirects to /login when setup is already complete") {
            testApplication {
                val ctx = installTestWebUi()
                ctx.fake.serverInfoResult = AppResult.Success(info(setupRequired = false))
                val response = webClient().get("/setup")
                response.status shouldBe HttpStatusCode.Found
                response.headers["Location"] shouldBe "/login"
            }
        }

        test("POST /setup success creates the root session and HX-Redirects home") {
            testApplication {
                val ctx = installTestWebUi()
                ctx.fake.serverInfoResult = AppResult.Success(info(setupRequired = true))
                ctx.fake.setupResult = AppResult.Success(sampleAuthSession())
                val client = webClient()
                client.get("/setup")
                val token = client.csrfToken()
                val response =
                    client.submitForm(
                        url = "/setup",
                        formParameters = parameters {
                            append("email", "root@x")
                            append("displayName", "Root")
                            append("password", "password1")
                        },
                    ) { header("X-CSRF-Token", token) }
                response.headers["HX-Redirect"] shouldBe "/"
            }
        }

        test("POST /setup loopback failure re-renders the form with the typed error") {
            testApplication {
                val ctx = installTestWebUi()
                ctx.fake.serverInfoResult = AppResult.Success(info(setupRequired = true))
                ctx.fake.setupResult = AppResult.Failure(AuthError.SetupAlreadyComplete())
                val client = webClient()
                client.get("/setup")
                val token = client.csrfToken()
                // valid-length password so RegisterRequest constructs and the loopback is reached
                val response =
                    client.submitForm(
                        url = "/setup",
                        formParameters = parameters {
                            append("email", "root@x")
                            append("displayName", "Root")
                            append("password", "password1")
                        },
                    ) { header("X-CSRF-Token", token) }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "Setup has already been completed."
            }
        }

        test("POST /setup with invalid input re-renders without reaching the loopback") {
            testApplication {
                val ctx = installTestWebUi()
                ctx.fake.serverInfoResult = AppResult.Success(info(setupRequired = true))
                // If the loopback were reached it would succeed and redirect; a blank display
                // name must short-circuit on RegisterRequest validation and re-render instead.
                ctx.fake.setupResult = AppResult.Success(sampleAuthSession())
                val client = webClient()
                client.get("/setup")
                val token = client.csrfToken()
                val response =
                    client.submitForm(
                        url = "/setup",
                        formParameters = parameters {
                            append("email", "root@x")
                            append("displayName", "")
                            append("password", "password1")
                        },
                    ) { header("X-CSRF-Token", token) }
                response.headers["HX-Redirect"] shouldBe null
                response.status shouldBe HttpStatusCode.OK
            }
        }
    })
