package com.calypsan.listenup.server.routes

import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.plugins.ACCESS_TOKEN_COOKIE
import com.calypsan.listenup.server.plugins.BLOB_READ_PROVIDER
import com.calypsan.listenup.server.testing.setupRootUser
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.cookie
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.plugin
import io.ktor.server.auth.AuthenticationRouteSelector
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.RoutingRoot
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

        // Every byte-serving GET the browser loads into an element, with a path that resolves to no
        // row — so the handler's own answer is 404 and the auth wall's is 401, which is the whole
        // distinction these assertions turn on. If one of these ever moves back behind JWT_PROVIDER
        // the browser breaks silently: the image simply never appears.
        val blobReadPaths =
            listOf(
                "/api/v1/books/no-such-book/cover",
                "/api/v1/covers/no-such-book",
                "/api/v1/books/no-such-book/documents/no-such-doc",
                "/api/v1/contributors/no-such-contributor/photo",
                "/api/v1/series/no-such-series/cover",
                "/api/v1/avatars/no-such-user",
            )

        test("a cookie authenticates every byte-serving GET") {
            bootedServer { token ->
                blobReadPaths.forEach { path ->
                    withClue(path) {
                        client
                            .get(path) { cookie(ACCESS_TOKEN_COOKIE, token) }
                            .status shouldNotBe HttpStatusCode.Unauthorized

                        client.get(path).status shouldBe HttpStatusCode.Unauthorized
                    }
                }
            }
        }

        test("nothing but a GET is mounted behind the cookie-bearing provider") {
            bootedServer {
                // The claim this whole change rests on is "the cookie is safe BECAUSE it only ever
                // reaches reads of bytes". Every other test here checks a route someone thought to
                // name; this one checks the routing tree itself, so a mutation added inside the
                // block later — the exact mistake that re-opens the widening — fails here without
                // anyone having remembered to write a test for it.
                val blobReadMounts = application.plugin(RoutingRoot).authSubtreesFor(BLOB_READ_PROVIDER)
                blobReadMounts.shouldNotBeEmpty()

                val methods = blobReadMounts.flatMap { it.methodsBelow() }
                methods.shouldNotBeEmpty()
                methods.forEach { method ->
                    withClue("$method is mounted behind $BLOB_READ_PROVIDER") {
                        method shouldBe HttpMethod.Get
                    }
                }
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
                val wsClient = createClient { install(WebSockets) }

                // Byte for byte the request a cross-site page's `new WebSocket(...)` produces: a
                // real upgrade, the cookie the browser attaches by itself, and no Authorization
                // header because script cannot add one. This is the whole exposure — a WebSocket
                // handshake is not subject to CORS.
                //
                // Asserted as "the handshake is refused" rather than as a 401, because the client
                // reports every failed upgrade as the same status-less IllegalStateException and
                // the engine will not let a caller hand-write the upgrade headers to see the raw
                // response. The pair is what carries the meaning: this one is refused, the
                // header-carried one below is not.
                shouldThrow<IllegalStateException> {
                    wsClient.webSocketSession("ws://localhost/api/rpc/authed") {
                        cookie(ACCESS_TOKEN_COOKIE, token)
                    }
                }

                // Same socket, same token, header carrier: the handshake completes and a session
                // opens. So the 401 above is the carrier being rejected, not the mount being
                // unreachable or the token being stale.
                wsClient
                    .webSocketSession("ws://localhost/api/rpc/authed") { bearerAuth(token) }
                    .close()
            }
        }
    })

/** Every routing node below this one, depth-first. */
private fun RoutingNode.nodesBelow(): List<RoutingNode> {
    val childNodes = children.filterIsInstance<RoutingNode>()
    return childNodes + childNodes.flatMap { it.nodesBelow() }
}

/** The `authenticate([provider])` mounts in this tree — one per block that names [provider]. */
private fun RoutingNode.authSubtreesFor(provider: String): List<RoutingNode> =
    nodesBelow().filter { node ->
        (node.selector as? AuthenticationRouteSelector)?.names?.contains(provider) == true
    }

/**
 * The HTTP methods every route below this node is registered for.
 *
 * A route's method lives in an [HttpMethodRouteSelector] somewhere along its chain, so collecting
 * them over the whole subtree enumerates what the mount actually accepts — no matter how the routes
 * beneath it were declared (typed `@Resource` builders and plain path strings both land here).
 */
private fun RoutingNode.methodsBelow(): List<HttpMethod> = nodesBelow().mapNotNull { (it.selector as? HttpMethodRouteSelector)?.method }
