package com.calypsan.listenup.server

import com.calypsan.listenup.api.PingService
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.server.application.plugin
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.ktor.client.rpcConfig
import kotlinx.rpc.krpc.serialization.json.json
import kotlinx.rpc.withService
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets

/**
 * Pins the inbound WebSocket frame cap on the RPC mounts.
 *
 * `/api/rpc/public` is reachable without a credential, and Ktor buffers an inbound frame in full
 * before any handler runs — so the per-IP throttle inside `AuthServiceImpl` cannot see, let alone
 * bound, the memory a single oversized frame costs. Ktor's default `maxFrameSize` is
 * `Long.MAX_VALUE`, i.e. no bound at all; `installCorePlugins` configures a real one.
 *
 * **Asserted through the plugin's configuration rather than by sending an oversized frame.** The
 * cap is 32 MiB, so a socket-level "too big" case would have to allocate and push more than that
 * through the in-process test transport on every run — prohibitive in a lane that already carries
 * thousands of specs. The plugin instance carries the very value the frame reader enforces, which
 * is the property under test; the round-trip below then proves the configured cap does not disturb
 * ordinary RPC traffic.
 */
class WebSocketFrameLimitTest :
    FunSpec({
        test("the RPC WebSockets plugin is configured with a bounded maximum frame size") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }
                // Round-trip first: `application { }` is lazy, and the plugin only exists once the
                // application has actually been built.
                pingOverPublicRpc()

                val configured = application.plugin(WebSockets).maxFrameSize

                configured shouldBe EXPECTED_MAX_FRAME_SIZE_BYTES
                // The bound that matters: anything short of this is Ktor's unbounded default.
                configured shouldBeLessThan Long.MAX_VALUE
            }
        }

        test("a frame within the configured maximum is accepted") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }

                pingOverPublicRpc()
            }
        }
    })

/**
 * The value `installCorePlugins` configures, restated here rather than imported: the constant is
 * private to the production file on purpose, and a test that read the same symbol it is pinning
 * would pass however that symbol changed.
 */
private const val EXPECTED_MAX_FRAME_SIZE_BYTES = 33_554_432L

/** Opens an RPC session over the public mount and round-trips one ordinary call through it. */
private suspend fun ApplicationTestBuilder.pingOverPublicRpc() {
    val rpcClient =
        createClient {
            install(ClientWebSockets)
            installKrpc()
        }
    val result =
        rpcClient
            .rpc("ws://localhost/api/rpc/public") {
                rpcConfig { serialization { json(contractJson) } }
            }.withService<PingService>()
            .ping()
    result.shouldBeInstanceOf<AppResult.Success<String>>().data shouldBe "pong"
}
