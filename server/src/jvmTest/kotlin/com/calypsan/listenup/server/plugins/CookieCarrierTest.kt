package com.calypsan.listenup.server.plugins

import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.server.auth.JwtConfiguration
import com.calypsan.listenup.server.auth.SessionLiveness
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.cookie
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication

/**
 * The third credential carrier: a cookie holding the access token.
 *
 * An `<img src>` cannot set an `Authorization` header, which is the same shape of problem the socket
 * ticket already solves for the `WebSocket` constructor — so it is solved at the same seam rather
 * than with a second auth system. The load-bearing property is precedence: a present header always
 * wins, so no native client's request is read any differently than it was before the cookie existed.
 */
class CookieCarrierTest :
    FunSpec({
        val jwt =
            JwtConfiguration(
                secret = "test-secret-that-is-long-enough-32",
                issuer = "listenup-test",
                audience = "listenup-test-clients",
            )

        fun tokenFor(sessionId: String) =
            jwt.issue(
                userId = UserId("user-1"),
                sessionId = SessionId(sessionId),
                role = UserRole.ADMIN,
            )

        fun ApplicationTestBuilder.gatedRoute() {
            application {
                installJwtAuth(jwt, SessionLiveness { true })
                routing {
                    authenticate(JWT_PROVIDER) {
                        get("/whoami") { call.respondText("ok") }
                    }
                }
            }
        }

        test("a cookie alone authenticates") {
            testApplication {
                gatedRoute()

                val response =
                    client.get("/whoami") {
                        cookie(ACCESS_TOKEN_COOKIE, tokenFor("session-1"))
                    }

                response.status shouldBe HttpStatusCode.OK
            }
        }

        test("a present header wins over a cookie") {
            testApplication {
                gatedRoute()

                // The cookie was never a token: success proves the header was the one read.
                val response =
                    client.get("/whoami") {
                        bearerAuth(tokenFor("session-2"))
                        cookie(ACCESS_TOKEN_COOKIE, "never-a-token")
                    }

                response.status shouldBe HttpStatusCode.OK
            }
        }

        test("no credential at all is rejected") {
            testApplication {
                gatedRoute()

                client.get("/whoami").status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("a malformed cookie is rejected rather than crashing") {
            testApplication {
                gatedRoute()

                client
                    .get("/whoami") { cookie(ACCESS_TOKEN_COOKIE, "garbage") }
                    .status shouldBe HttpStatusCode.Unauthorized

                // A value outside RFC 7235's token68 alphabet makes HttpAuthHeader.Single throw, and a
                // browser writes whatever it was handed — that must answer 401, never 500.
                client
                    .get("/whoami") { cookie(ACCESS_TOKEN_COOKIE, "not a token!") }
                    .status shouldBe HttpStatusCode.Unauthorized
            }
        }
    })
