@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.server.plugins

import com.calypsan.listenup.server.auth.SocketTicketStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.cookie
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * What [BLOB_READ_PROVIDER] accepts — a header or [ACCESS_TOKEN_COOKIE] — and, the half that makes
 * the cookie safe to have at all, what `JWT_PROVIDER` still refuses. The sibling spec is
 * [JwtAuthTest], which covers `JWT_PROVIDER`'s own carriers; the shared fixture is in
 * `JwtAuthFixtures.kt`.
 *
 * An `<img src>` cannot set an `Authorization` header, which is the same shape of problem the socket
 * ticket already solves for the `WebSocket` constructor. But a cookie differs from a ticket in the
 * one way that matters: the browser attaches it to cross-site requests by itself, and a WebSocket
 * upgrade is not subject to CORS. So the cookie gets its own provider, worn only by the byte-serving
 * GETs, while `JWT_PROVIDER` — which gates the whole RPC surface and every mutating route — still
 * reads a header or a ticket and nothing else.
 *
 * Three properties are load-bearing here: a valid header always wins over a cookie; a header that is
 * *present but bad* is never quietly retried as a cookie; and a cookie buys nothing on
 * `JWT_PROVIDER`. `CookieCarrierScopeTest` pins the other half — which real routes each provider
 * actually gates.
 */
class CookieCarrierTest :
    FunSpec({

        test("a cookie alone authenticates") {
            testApplication {
                gatedRoute(BLOB_READ_PROVIDER)

                val response =
                    client.get("/gated") {
                        cookie(ACCESS_TOKEN_COOKIE, tokenFor("session-1"))
                    }

                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldBe FIXTURE_USER_ID
            }
        }

        test("a valid header wins over a cookie") {
            testApplication {
                gatedRoute(BLOB_READ_PROVIDER)

                // The cookie was never a token: success proves the header was the one read.
                val response =
                    client.get("/gated") {
                        bearerAuth(tokenFor("session-2"))
                        cookie(ACCESS_TOKEN_COOKIE, "never-a-token")
                    }

                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldBe FIXTURE_USER_ID
            }
        }

        test("a bad header is not retried as a cookie") {
            testApplication {
                gatedRoute(BLOB_READ_PROVIDER)

                // The inverse of the case above, and the one that actually guards the boundary:
                // "header wins" must mean the header is the ONLY thing read when present, not that
                // it is tried first. A fallback-to-cookie-on-failure would hand every cross-site
                // request a second chance with the credential the browser attaches by itself.
                client
                    .get("/gated") {
                        bearerAuth("not-a-real-token")
                        cookie(ACCESS_TOKEN_COOKIE, tokenFor("session-3"))
                    }.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("no credential at all is rejected") {
            testApplication {
                gatedRoute(BLOB_READ_PROVIDER)

                client.get("/gated").status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("a cookie that is not a valid token is rejected") {
            testApplication {
                gatedRoute(BLOB_READ_PROVIDER)

                // Well-formed as a credential (token68), so it reaches JWT verification and fails there.
                client
                    .get("/gated") { cookie(ACCESS_TOKEN_COOKIE, "garbage") }
                    .status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("a cookie that is not a valid credential shape is 401, not a 500") {
            testApplication {
                gatedRoute(BLOB_READ_PROVIDER)

                // A space and `!` are outside RFC 7235's token68 alphabet, so building the
                // HttpAuthHeader raises ParseException — inside a block that cannot throw without
                // turning a bad credential into a 500. A browser sends back whatever it was handed,
                // so this is reachable by a client bug, not just by an attacker.
                client
                    .get("/gated") { cookie(ACCESS_TOKEN_COOKIE, "not a token!") }
                    .status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("a socket ticket is not a credential here") {
            testApplication {
                val tickets = SocketTicketStore(Clock.System)
                val ticket = tickets.issue(tokenFor("session-4"))
                gatedRoute(BLOB_READ_PROVIDER, tickets)

                // A ticket is spent by the one connection it opens; a page of covers is many
                // requests. Accepting one here would burn it on the first image and leave the
                // rest — and the socket it was minted for — unauthenticated.
                client.get("/gated?ticket=$ticket").status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("the header-or-ticket provider does not read the cookie at all") {
            testApplication {
                gatedRoute(JWT_PROVIDER)

                // The same token that authenticates BLOB_READ_PROVIDER above, in the same cookie.
                // 401 here is the whole reason the cookie carrier is a separate provider: this is
                // the credential a cross-site page could get the browser to send for free, and
                // JWT_PROVIDER gates the RPC surface and every mutation.
                val token = tokenFor("session-5")
                client
                    .get("/gated") { cookie(ACCESS_TOKEN_COOKIE, token) }
                    .status shouldBe HttpStatusCode.Unauthorized

                // And the token really is good — so the 401 above is about the carrier, not the token.
                client.get("/gated") { bearerAuth(token) }.status shouldBe HttpStatusCode.OK
            }
        }
    })
