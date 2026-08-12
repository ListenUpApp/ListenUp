package com.calypsan.listenup.server.routes

import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.plugins.ACCESS_TOKEN_COOKIE
import com.calypsan.listenup.server.testing.setupRootUser
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.cookie
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.close
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files

/**
 * Where the access-token cookie is worth something, and — the point of the spec — where it is not.
 *
 * The cookie exists so an `<img src>` can fetch a cover, and a browser attaches it to cross-site
 * requests without being asked. A WebSocket upgrade is not subject to CORS, so had the cookie been
 * accepted on the provider gating `/api/rpc/authed`, a cross-site page could have opened the whole
 * authenticated RPC surface on the visitor's session. The tests below fix both halves in place: the
 * cover read takes the cookie, the mutating route and the RPC mount do not.
 *
 * Boots the real `Application.module()` rather than a stub topology, because what is being asserted
 * IS the topology — which provider each route was mounted under.
 */
class CookieCarrierScopeTest :
    FunSpec({

        // A booted server plus a ROOT caller's token. ROOT bypasses every access policy, so a
        // status other than 401 can only mean the request cleared the auth wall.
        suspend fun bootedServer(block: suspend ApplicationTestBuilder.(token: String) -> Unit) {
            val libraryRoot = Files.createTempDirectory("listenup-cookie-scope-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString(), rescanOnStartup = false)
                    application { module() }
                    block(setupRootUser().token)
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }

        test("a cookie authenticates a cover GET") {
            bootedServer { token ->
                // No such book, so the gated handler answers 404 — which is the proof: an
                // unauthenticated caller never reaches the handler and gets 401 instead.
                val response =
                    client.get("/api/v1/books/no-such-book/cover") {
                        cookie(ACCESS_TOKEN_COOKIE, token)
                    }

                response.status shouldBe HttpStatusCode.NotFound
                client.get("/api/v1/books/no-such-book/cover").status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("a cookie is rejected on a mutating book route") {
            bootedServer { token ->
                client
                    .delete("/api/v1/books/no-such-book/cover") { cookie(ACCESS_TOKEN_COOKIE, token) }
                    .status shouldBe HttpStatusCode.Unauthorized

                // Same route, same token, header carrier: it gets in. So the 401 above is the
                // carrier being refused, not the route being absent or the token being stale.
                client
                    .delete("/api/v1/books/no-such-book/cover") { bearerAuth(token) }
                    .status shouldNotBe HttpStatusCode.Unauthorized
            }
        }

        test("a cookie is rejected on the authed RPC mount") {
            bootedServer { token ->
                // A real upgrade, because that is the whole exposure: a WebSocket handshake is not
                // subject to CORS, so a cross-site page can issue exactly this — cookie attached by
                // the browser, no header available to it. Ktor's client raises on any handshake
                // that does not answer 101, and a refused credential is what makes this one fail.
                // (A plain GET proves nothing here: the socket route selects on the upgrade
                // headers, so an ordinary request fails routing with 400 before auth ever runs.)
                val wsClient = createClient { install(WebSockets) }
                shouldThrowAny {
                    wsClient.webSocketSession("ws://localhost/api/rpc/authed") {
                        cookie(ACCESS_TOKEN_COOKIE, token)
                    }
                }

                // Same socket, same token, header carrier: the handshake completes. So the refusal
                // above is the carrier being rejected, not the mount being unreachable.
                wsClient
                    .webSocketSession("ws://localhost/api/rpc/authed") { bearerAuth(token) }
                    .close()
            }
        }
    })
