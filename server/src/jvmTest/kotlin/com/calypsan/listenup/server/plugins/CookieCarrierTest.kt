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
 * The cookie carrier, and the wall around it.
 *
 * An `<img src>` cannot set an `Authorization` header, which is the same shape of problem the socket
 * ticket already solves for the `WebSocket` constructor. But a cookie differs from a ticket in the
 * one way that matters: the browser attaches it to cross-site requests by itself, and a WebSocket
 * upgrade is not subject to CORS. So the cookie gets its own provider — [DOM_FETCH_PROVIDER], worn
 * only by the byte-serving GETs a DOM element fetches — while [JWT_PROVIDER], which gates the whole
 * RPC surface and every mutating route, still reads a header or a ticket and nothing else.
 *
 * Two properties are load-bearing, one per provider: a present header always wins over a cookie, so
 * no native client's request is read any differently than before; and a cookie buys nothing at all
 * on [JWT_PROVIDER], so the widening cannot reach the surface that would make it exploitable.
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

        fun ApplicationTestBuilder.gatedRoute(provider: String) {
            application {
                installJwtAuth(jwt, SessionLiveness { true })
                routing {
                    authenticate(provider) {
                        get("/whoami") { call.respondText("ok") }
                    }
                }
            }
        }

        test("a cookie alone authenticates") {
            testApplication {
                gatedRoute(DOM_FETCH_PROVIDER)

                val response =
                    client.get("/whoami") {
                        cookie(ACCESS_TOKEN_COOKIE, tokenFor("session-1"))
                    }

                response.status shouldBe HttpStatusCode.OK
            }
        }

        test("a present header wins over a cookie") {
            testApplication {
                gatedRoute(DOM_FETCH_PROVIDER)

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
                gatedRoute(DOM_FETCH_PROVIDER)

                client.get("/whoami").status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("a malformed cookie is rejected rather than crashing") {
            testApplication {
                gatedRoute(DOM_FETCH_PROVIDER)

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

        test("the header-or-ticket provider does not read the cookie at all") {
            testApplication {
                gatedRoute(JWT_PROVIDER)

                // The same token that authenticates DOM_FETCH_PROVIDER above, in the same cookie.
                // 401 here is the whole reason the cookie carrier is a separate provider: this
                // is the credential a cross-site page could get the browser to send for free.
                val token = tokenFor("session-4")
                client
                    .get("/whoami") { cookie(ACCESS_TOKEN_COOKIE, token) }
                    .status shouldBe HttpStatusCode.Unauthorized

                // And the token really is good — so the 401 above is about the carrier, not the token.
                client.get("/whoami") { bearerAuth(token) }.status shouldBe HttpStatusCode.OK
            }
        }
    })
