package com.calypsan.listenup.client.diagnostics

import com.calypsan.listenup.api.AuthServicePublic
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.toWebSocketScheme
import io.ktor.client.HttpClient
import kotlinx.browser.window
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.serialization.json.json
import kotlinx.rpc.withService

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
