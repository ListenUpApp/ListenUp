@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.server.plugins

import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.server.auth.JwtConfiguration
import com.calypsan.listenup.server.auth.SessionLiveness
import com.calypsan.listenup.server.auth.SocketTicketStore
import com.calypsan.listenup.server.logging.ListenUpLoggerFactory
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.slf4j.event.Level
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * The bearer provider reads its credential from the `Authorization` header first and from a
 * single-use `ticket` query parameter second — the browser path, because the `WebSocket`
 * constructor has no header parameter and Ktor's JS engine therefore drops the header the `Auth`
 * plugin wrote. Header-first is what keeps every native client byte-for-byte unchanged.
 *
 * The query carries a TICKET and never an access token: a URL lands in a reverse-proxy access log,
 * so what goes there has to be single-use and seconds-lived. The test below that rejects a valid
 * JWT in the query is the one holding that line.
 */
class JwtAuthTest :
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

        test("a valid token in the Authorization header authenticates") {
            testApplication {
                application {
                    installJwtAuth(jwt, SessionLiveness { true })
                    routing {
                        authenticate(JWT_PROVIDER) {
                            get("/gated") { call.respondText(call.userPrincipalOrNull()?.userId?.value ?: "none") }
                        }
                    }
                }

                val response = client.get("/gated") { bearerAuth(tokenFor("session-1")) }

                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldBe "user-1"
            }
        }

        test("a ticket in the query alone authenticates, redeeming to the token it stands for") {
            testApplication {
                val tickets = SocketTicketStore(Clock.System)
                val ticket = tickets.issue(tokenFor("session-2"))
                application {
                    installJwtAuth(jwt, SessionLiveness { true }, tickets)
                    routing {
                        authenticate(JWT_PROVIDER) {
                            get("/gated") { call.respondText(call.userPrincipalOrNull()?.userId?.value ?: "none") }
                        }
                    }
                }

                val response = client.get("/gated?ticket=$ticket")

                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldBe "user-1"
            }
        }

        test("a ticket is spent by the connection it opens, so a replay is 401") {
            testApplication {
                val tickets = SocketTicketStore(Clock.System)
                val ticket = tickets.issue(tokenFor("session-2"))
                application {
                    installJwtAuth(jwt, SessionLiveness { true }, tickets)
                    routing {
                        authenticate(JWT_PROVIDER) {
                            get("/gated") { call.respondText("reached") }
                        }
                    }
                }

                client.get("/gated?ticket=$ticket").status shouldBe HttpStatusCode.OK

                // This is the property that makes a URL-borne credential survivable: whatever a
                // proxy log captured is already useless.
                client.get("/gated?ticket=$ticket").status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("a raw access token in the query is REJECTED — the ticket path is not a token path") {
            testApplication {
                application {
                    installJwtAuth(jwt, SessionLiveness { true }, SocketTicketStore(Clock.System))
                    routing {
                        authenticate(JWT_PROVIDER) {
                            get("/gated") { call.respondText("reached") }
                        }
                    }
                }

                // A perfectly valid JWT. Accepting it here would put a 15-minute credential in
                // every reverse-proxy access log, which is the entire thing tickets exist to avoid.
                client.get("/gated?ticket=${tokenFor("session-9")}").status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("the Authorization header wins when both are present and the ticket is junk") {
            testApplication {
                application {
                    installJwtAuth(jwt, SessionLiveness { true }, SocketTicketStore(Clock.System))
                    routing {
                        authenticate(JWT_PROVIDER) {
                            get("/gated") { call.respondText(call.userPrincipalOrNull()?.userId?.value ?: "none") }
                        }
                    }
                }

                // The ticket was never issued: success proves it was never read.
                val response =
                    client.get("/gated?ticket=never-issued") {
                        bearerAuth(tokenFor("session-3"))
                    }

                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldBe "user-1"
            }
        }

        test("neither a header nor a ticket is 401") {
            testApplication {
                application {
                    installJwtAuth(jwt, SessionLiveness { true }, SocketTicketStore(Clock.System))
                    routing {
                        authenticate(JWT_PROVIDER) {
                            get("/gated") { call.respondText("reached") }
                        }
                    }
                }

                client.get("/gated").status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("a ticket that is not a valid credential shape is 401, not a 500") {
            testApplication {
                application {
                    installJwtAuth(jwt, SessionLiveness { true }, SocketTicketStore(Clock.System))
                    routing {
                        authenticate(JWT_PROVIDER) {
                            get("/gated") { call.respondText("reached") }
                        }
                    }
                }

                // `!` is outside RFC 7235's token68 alphabet — building an HttpAuthHeader.Single from
                // it raises ParseException, which must never escape as a 500.
                client.get("/gated?ticket=not%20a%20ticket%21").status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("with no ticket store configured, a ticket in the query cannot authenticate") {
            testApplication {
                application {
                    installJwtAuth(jwt, SessionLiveness { true })
                    routing {
                        authenticate(JWT_PROVIDER) {
                            get("/gated") { call.respondText("reached") }
                        }
                    }
                }

                client.get("/gated?ticket=anything").status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("a failed JWT verification is logged at WARN without the token") {
            val capture = ListenUpLoggerFactory.installTestCapture()
            try {
                logJwtRejection("token verification failed")
                val event = capture.events.lastOrNull { it.loggerName.contains("JwtAuth") }.shouldNotBeNull()
                event.level shouldBe Level.WARN
                event.message.contains("token verification failed").shouldBeTrue()
            } finally {
                ListenUpLoggerFactory.removeTestCapture()
            }
        }
    })
