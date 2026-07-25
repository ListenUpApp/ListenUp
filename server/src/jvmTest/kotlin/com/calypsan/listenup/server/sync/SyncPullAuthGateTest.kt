package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.SyncStreamService
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.testing.authedService
import com.calypsan.listenup.server.testing.setupRootUser
import com.calypsan.listenup.server.testing.shouldSucceed
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.core.spec.style.FunSpec
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.ktor.client.rpcConfig
import kotlinx.rpc.krpc.serialization.json.json
import kotlinx.rpc.withService

/**
 * The sync pull surface sits behind the JWT wall.
 *
 * Catch-up, digest and domain discovery are [SyncStreamService] methods on the authed RPC mount,
 * so the gate is no longer per-route — it is `authenticate(JWT_PROVIDER)` wrapping
 * `/api/rpc/authed` (see `RpcRoutes.jvm.kt`). This replaces the per-route 401 checks the deleted
 * REST sync endpoints carried.
 *
 * Both tests drive a real kRPC client, because that is what a client actually meets. Asserting an
 * HTTP status on a plain `GET /api/rpc/authed` does **not** work: the WebSocket route rejects a
 * non-upgrade request with 400 before the auth challenge is ever reached, so such a test would
 * pass for the wrong reason.
 */
class SyncPullAuthGateTest :
    FunSpec({

        test("an unauthenticated caller cannot reach the pull surface") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }

                val unauthenticated = syncServiceWithoutToken()

                shouldThrowAny { unauthenticated.listDomains() }
            }
        }

        test("the same call succeeds once a bearer token is present") {
            // The control for the test above. Without it, the failure could come from anything —
            // a missing mount, a serialization fault — and the gate assertion would be vacuous.
            testApplication {
                useIsolatedTestConfig()
                application { module() }
                val root = setupRootUser()

                val domains = authedService<SyncStreamService>(root.token).listDomains().shouldSucceed()

                domains.shouldNotBeEmpty()
            }
        }
    })

/** A [SyncStreamService] proxy on the authed mount carrying no bearer token. */
private suspend fun ApplicationTestBuilder.syncServiceWithoutToken(): SyncStreamService =
    createClient {
        install(WebSockets)
        installKrpc()
    }.rpc("ws://localhost/api/rpc/authed") {
        rpcConfig { serialization { json(contractJson) } }
    }.withService<SyncStreamService>()
