package com.calypsan.listenup.client.diagnostics

import com.calypsan.listenup.api.AuthServicePublic
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.toWebSocketScheme
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import kotlinx.browser.window
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.serialization.json.json
import kotlinx.rpc.withService
import kotlin.coroutines.cancellation.CancellationException

/**
 * What a browser RPC-transport self-check observed. Plain values, same reasoning as
 * [ClientGraphProbe]: the raw `HttpClient` / kotlinx.rpc proxy machinery is `internal` to this
 * module, so the result crosses to a browser test without exposing either.
 */
data class RpcTransportProbe(
    /** True once a service proxy was obtained and `login` returned any [AppResult] over the wire. */
    val socketOpened: Boolean,
    /** [com.calypsan.listenup.api.error.AppError.code] from a failed login, or null on [AppResult.Success]. */
    val errorCode: String?,
)

/**
 * Opens a real kotlinx.rpc WebSocket **from inside a browser** and proves a typed [AppResult]
 * survives the round trip. This is the proof the whole web-client direction turns on: nothing in
 * this repo had ever opened that socket from a browser before this probe.
 *
 * Deliberately builds its own minimal client rather than reusing [com.calypsan.listenup.client.data.remote.ApiClientFactory]
 * — `installKrpc()` (which installs `WebSockets` itself) plus kotlinx.rpc's json serialization,
 * nothing else. A failure here is then attributable to the socket itself, not to the production
 * client's retry / 401-heal / timeout stack, which is exercised separately once the socket is
 * known good.
 *
 * Dials the page's OWN origin ([window]'s `location.origin`, translated to `ws`/`wss`) plus the
 * public RPC mount — never an absolute server URL. Production serves the bundle and the RPC mount
 * from the same origin (Ktor), and the dev proxy (`vite.config.ts`, `ws: true`) reproduces exactly
 * that; dialing an absolute URL would prove a cross-origin topology that never ships.
 *
 * [email] and [password] are expected to match no account — see the call site. `login` failing
 * with a typed [com.calypsan.listenup.api.error.AuthError.InvalidCredentials] is a **stronger**
 * proof than a happy path: it needs no seeded user, and a transport that cannot carry a typed
 * failure cannot carry a typed success either.
 */
suspend fun probeRpcTransport(
    email: String,
    password: String,
): RpcTransportProbe {
    val mountUrl = toWebSocketScheme(window.location.origin) + PUBLIC_RPC_MOUNT

    val client =
        HttpClient {
            installKrpc {
                serialization { json(contractJson) }
            }
        }

    return try {
        val service = client.rpc(mountUrl).withService<AuthServicePublic>()
        when (val result = service.login(LoginRequest(email = email, password = password))) {
            is AppResult.Success -> RpcTransportProbe(socketOpened = true, errorCode = null)
            is AppResult.Failure -> RpcTransportProbe(socketOpened = true, errorCode = result.error.code)
        }
    } finally {
        client.close()
    }
}

/** The pre-auth kotlinx.rpc mount — mirrors `RpcPolicy.Public.mount` in `data/remote/RpcChannel.kt`. */
private const val PUBLIC_RPC_MOUNT = "/api/rpc/public"

/**
 * What the browser did with the production `ApiClientFactory` shape of client — specifically its
 * `install(WebSockets) { pingIntervalMillis = ... }` keepalive block, installed a SECOND time (with
 * an empty config) by `installKrpc()`, exactly as `RpcProxyCache.rpcClient()` derives the real RPC
 * client from `ApiClientFactory.getClient()` via `.config { installKrpc { ... } }`.
 */
data class ProductionWebSocketConfigProbe(
    /** True once the mirrored client opened a real RPC socket and `login` returned any [AppResult]. */
    val rpcSocketOpened: Boolean,
    /** [com.calypsan.listenup.api.error.AppError.code] from the failed login, or null on success. */
    val loginErrorCode: String?,
    /**
     * True if setting `pingIntervalMillis` directly on an OPEN [io.ktor.client.plugins.websocket.DefaultClientWebSocketSession]
     * threw. This is the code path `install(WebSockets) { pingIntervalMillis = ... }` would need to
     * reach for the keepalive to have any effect — if the browser's own live session rejects it here,
     * that is direct evidence the setting cannot work on this engine, independent of how the config
     * plumbing merges.
     */
    val settingPingIntervalOnLiveSessionThrew: Boolean,
    /** `Throwable.message` from the above, when it threw. */
    val pingIntervalExceptionMessage: String?,
)

/**
 * Opens a real kotlinx.rpc WebSocket using a client shaped like the PRODUCTION `ApiClientFactory` —
 * not the minimal one [probeRpcTransport] uses. Specifically mirrors:
 *  - `ApiClientFactory`'s `install(HttpTimeout) { requestTimeoutMillis = 30_000; connectTimeoutMillis =
 *    10_000; socketTimeoutMillis = 30_000 }` block.
 *  - `ApiClientFactory`'s `install(WebSockets) { pingIntervalMillis = 15_000 }` keepalive block (see
 *    `WS_PING_INTERVAL_MS` there).
 *  - `RpcProxyCache.rpcClient()`'s `.config { installKrpc { ... } }` derivation, which installs
 *    `WebSockets` a SECOND time (with an empty config block) on top of the first — the real production
 *    shape, not a hypothetical.
 *
 * Answers two questions empirically, in a real browser, rather than from documentation:
 *  1. Does any of the above throw at client-construction or socket-open time? (`rpcSocketOpened` /
 *     the absence of a thrown exception escaping this function answers "no" if it returns normally.)
 *  2. Does the ping-interval setting the config plumbing accepted actually reach the live session, or
 *     is it a silent no-op? Answered directly by opening a raw (non-RPC) [io.ktor.client.plugins.websocket.webSocket]
 *     session on the SAME client and setting `pingIntervalMillis` on it post-connect — the identical
 *     property the plugin machinery would need to set for the config value to matter.
 */
suspend fun probeProductionWebSocketConfig(
    email: String,
    password: String,
): ProductionWebSocketConfigProbe {
    val wsUrl = toWebSocketScheme(window.location.origin) + PUBLIC_RPC_MOUNT

    val client =
        HttpClient {
            // Mirrors ApiClientFactory's HttpTimeout block (requestTimeoutMillis / connectTimeoutMillis
            // / socketTimeoutMillis = 30_000 / 10_000 / 30_000).
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 30_000
            }
            // Mirrors ApiClientFactory's WS_PING_INTERVAL_MS keepalive block.
            install(WebSockets) {
                pingIntervalMillis = 15_000
            }
            // Mirrors RpcProxyCache.rpcClient()'s `.config { installKrpc { ... } }` derivation —
            // installs WebSockets a second time, with an empty config block, on top of the first.
            installKrpc {
                serialization { json(contractJson) }
            }
        }

    return try {
        val service = client.rpc(wsUrl).withService<AuthServicePublic>()
        val loginErrorCode =
            when (val result = service.login(LoginRequest(email = email, password = password))) {
                is AppResult.Success -> null
                is AppResult.Failure -> result.error.code
            }

        var settingPingIntervalThrew = false
        var pingIntervalExceptionMessage: String? = null
        try {
            client.webSocket(wsUrl) {
                pingIntervalMillis = 1_000
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            settingPingIntervalThrew = true
            pingIntervalExceptionMessage = e.message
        }

        ProductionWebSocketConfigProbe(
            rpcSocketOpened = true,
            loginErrorCode = loginErrorCode,
            settingPingIntervalOnLiveSessionThrew = settingPingIntervalThrew,
            pingIntervalExceptionMessage = pingIntervalExceptionMessage,
        )
    } finally {
        client.close()
    }
}
