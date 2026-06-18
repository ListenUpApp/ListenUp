package com.calypsan.listenup.web.routes

import com.calypsan.listenup.api.dto.ServerInfo
import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.web.session.WebSession
import com.calypsan.listenup.web.session.WebSessionStore
import com.calypsan.listenup.web.testing.installTestWebUi
import com.calypsan.listenup.web.testing.webClient
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.cookie
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication

private fun info(setupRequired: Boolean) =
    ServerInfo(
        name = "ListenUp", version = "0.0.1", apiVersion = "v1",
        setupRequired = setupRequired, registrationPolicy = RegistrationPolicy.OPEN, instanceId = "i1",
    )

class EntryRoutesTest :
    FunSpec({
        test("GET / redirects to /setup when setup is required") {
            testApplication {
                val ctx = installTestWebUi()
                ctx.fake.serverInfoResult = AppResult.Success(info(setupRequired = true))
                val response = webClient().get("/")
                response.status shouldBe HttpStatusCode.Found
                response.headers["Location"] shouldBe "/setup"
            }
        }

        test("GET / redirects to /login when not set up and no session cookie") {
            testApplication {
                val ctx = installTestWebUi()
                ctx.fake.serverInfoResult = AppResult.Success(info(setupRequired = false))
                val response = webClient().get("/")
                response.status shouldBe HttpStatusCode.Found
                response.headers["Location"] shouldBe "/login"
            }
        }

        test("GET / renders the home placeholder for a valid session") {
            testApplication {
                val store = WebSessionStore()
                val ctx = installTestWebUi(store = store)
                ctx.fake.serverInfoResult = AppResult.Success(info(setupRequired = false))
                val cookieId = store.newCookieId()
                store.put(
                    cookieId,
                    WebSession(
                        sessionId = SessionId("s1"), userId = UserId("u1"), role = UserRole.MEMBER,
                        accessToken = AccessToken("at"), refreshToken = RefreshToken("rt"),
                        accessExpiresAt = 9_999_999_999_999L,
                    ),
                )
                val response = webClient().get("/") { cookie("lu_session", cookieId) }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "Your library, in the browser."
            }
        }
    })
