package com.calypsan.listenup.web

import com.calypsan.listenup.client.diagnostics.probeProductionWebSocketConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.random.Random

/**
 * Round 5 of the web-toolchain-decoupling spike (see
 * `docs/superpowers/findings/2026-08-08-web-toolchain-decoupling-spike.md`): what a browser
 * actually enforces on the production `ApiClientFactory` shape of RPC client, not the minimal one
 * [RpcTransportTest] proves the socket with.
 *
 * Two things are checked empirically, in a real browser:
 *  1. `install(HttpTimeout) { ... }` + `install(WebSockets) { pingIntervalMillis = ... }` +
 *     `installKrpc()` (which installs `WebSockets` a SECOND time — the real production shape, since
 *     `RpcProxyCache.rpcClient()` derives the RPC client from `ApiClientFactory.getClient()` via
 *     `.config { installKrpc { ... } }`) does not throw at construction or socket-open time, and a
 *     typed [com.calypsan.listenup.api.error.AppError] still survives the round trip through it —
 *     same proof shape as [RpcTransportTest], but through the heavier client.
 *  2. Setting `pingIntervalMillis` directly on a live (already-connected)
 *     [io.ktor.client.plugins.websocket.DefaultClientWebSocketSession] THROWS on `ktor-client-js` —
 *     confirmed by reading the compiled output (`JsWebSocketSession.set_pingIntervalMillis_hq8mas_k$`
 *     unconditionally throws `WebSocketException("Websocket ping-pong is not supported in JS
 *     engine.")`) and now confirmed at runtime here. Because `JsWebSocketSession` already implements
 *     `DefaultWebSocketSession`, the `WebSockets` plugin's `convertSessionToDefault` returns it
 *     UNCHANGED instead of wrapping it with the configured ping interval — so that throwing setter is
 *     never reached through the plugin's normal config path. The practical result: `pingIntervalMillis`
 *     set via `install(WebSockets) { ... }` is a SILENT NO-OP on this engine, not a construction-time
 *     failure — worse than a throw, because the client looks configured and is not. See the comment
 *     on `install(WebSockets)` in `ApiClientFactory.kt` for the consequence.
 */
class ProductionWebSocketConfigTest :
    FunSpec({
        // Same reasoning as RpcTransportTest: this spec needs a live server, so it is loaded (and
        // discovered) by both browser lanes but only enabled where `pnpm test:auth` booted one —
        // see that spec's KDoc for why this is a declared configuration difference, not an escape
        // hatch, and why the discovered-test-count floors are what keep it honest.
        val serverBooted = js("window.__LU_SERVER_URL").unsafeCast<String?>() != null

        test(
            "the production WebSockets/HttpTimeout shape opens a socket, carries a typed AppError, " +
                "and its ping-interval setting cannot reach a live session",
        ).config(enabled = serverBooted) {
            val probe =
                probeProductionWebSocketConfig(
                    email = "prod-ws-config-probe-${Random.nextInt(0, Int.MAX_VALUE)}@example.invalid",
                    password = "does-not-exist-password",
                )

            probe.rpcSocketOpened shouldBe true
            probe.loginErrorCode shouldBe "AUTH_INVALID_CREDENTIALS"
            probe.settingPingIntervalOnLiveSessionThrew shouldBe true
            probe.pingIntervalExceptionMessage shouldBe "Websocket ping-pong is not supported in JS engine."
        }
    })
