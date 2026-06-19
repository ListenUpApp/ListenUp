package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.InstanceService
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.dto.ServerInfo
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.ktor.client.rpcConfig
import kotlinx.rpc.krpc.serialization.json.json
import kotlinx.rpc.withService

/**
 * End-to-end reachability test for [InstanceService] over the public kotlinx.rpc
 * surface — the pre-auth call the client makes on first connect to verify a server.
 *
 * Boots the full `Application.module()` via [testApplication], connects an
 * unauthenticated [InstanceService] proxy to `/api/rpc/public`, and asserts
 * [InstanceService.getServerInfo] returns the seeded server identity. On a fresh
 * isolated DB no users exist, so [ServerInfo.setupRequired] must be `true`.
 */
class InstanceServiceRpcTest :
    FunSpec({

        test("getServerInfo over the public RPC surface reports setupRequired on a fresh instance") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }

                val rpcClient =
                    createClient {
                        install(WebSockets)
                        installKrpc()
                    }

                val service =
                    rpcClient
                        .rpc("ws://localhost/api/rpc/public") {
                            rpcConfig { serialization { json(contractJson) } }
                        }.withService<InstanceService>()

                val success =
                    service
                        .getServerInfo()
                        .shouldBeInstanceOf<AppResult.Success<ServerInfo>>()

                success.data.name shouldBe "ListenUp"
                success.data.apiVersion shouldBe "v1"
                success.data.setupRequired shouldBe true
                success.data.registrationPolicy shouldBe RegistrationPolicy.OPEN
            }
        }
    })
