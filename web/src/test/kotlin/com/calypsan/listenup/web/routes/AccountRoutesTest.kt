package com.calypsan.listenup.web.routes

import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.SessionSummary
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.web.session.WebSession
import com.calypsan.listenup.web.session.WebSessionStore
import com.calypsan.listenup.web.testing.installTestWebUi
import com.calypsan.listenup.web.testing.webClient
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.cookie
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication

private fun seedSession(store: WebSessionStore): String {
    val cookieId = store.newCookieId()
    store.put(
        cookieId,
        WebSession(
            sessionId = SessionId("s1"), userId = UserId("u1"), role = UserRole.MEMBER,
            accessToken = AccessToken("at"), refreshToken = RefreshToken("rt"),
            accessExpiresAt = 9_999_999_999_999L,
        ),
    )
    return cookieId
}

class AccountRoutesTest :
    FunSpec({
        test("POST /logout without a session cookie redirects to /login") {
            testApplication {
                installTestWebUi()
                val response =
                    webClient().post("/logout") {
                        cookie("lu_csrf", "t1"); header("X-CSRF-Token", "t1")
                    }
                response.status shouldBe HttpStatusCode.Found
                response.headers["Location"] shouldBe "/login"
            }
        }

        test("POST /logout clears the store entry and expires the cookie") {
            testApplication {
                val store = WebSessionStore()
                installTestWebUi(store = store)
                val cookieId = seedSession(store)
                val response =
                    webClient().post("/logout") {
                        cookie("lu_session", cookieId)
                        cookie("lu_csrf", "t1"); header("X-CSRF-Token", "t1")
                    }
                response.headers["HX-Redirect"] shouldBe "/login"
                store.get(cookieId).shouldBeNull()
            }
        }

        test("GET /account/sessions lists the device sessions") {
            testApplication {
                val store = WebSessionStore()
                val ctx = installTestWebUi(store = store)
                ctx.fake.listSessionsResult =
                    AppResult.Success(
                        listOf(
                            SessionSummary(
                                id = SessionId("s1"), label = "Firefox on Linux",
                                createdAt = 0L, lastUsedAt = 0L, current = true,
                            ),
                        ),
                    )
                val cookieId = seedSession(store)
                val response = webClient().get("/account/sessions") { cookie("lu_session", cookieId) }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "Firefox on Linux"
            }
        }

        test("GET /account/sessions redirects to /login without a session") {
            testApplication {
                installTestWebUi()
                val response = webClient().get("/account/sessions")
                response.status shouldBe HttpStatusCode.Found
                response.headers["Location"] shouldBe "/login"
            }
        }

        test("DELETE /account/sessions/{id} revokes via the loopback client") {
            testApplication {
                val store = WebSessionStore()
                val ctx = installTestWebUi(store = store)
                ctx.fake.revokeResult = AppResult.Success(Unit)
                ctx.fake.listSessionsResult = AppResult.Success(emptyList())
                val cookieId = seedSession(store)
                val response =
                    webClient().delete("/account/sessions/s1") {
                        cookie("lu_session", cookieId)
                        cookie("lu_csrf", "t1"); header("X-CSRF-Token", "t1")
                    }
                response.status shouldBe HttpStatusCode.OK
            }
        }
    })
