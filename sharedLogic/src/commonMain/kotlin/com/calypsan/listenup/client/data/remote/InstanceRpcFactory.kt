package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.InstanceService
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.error.TransportError
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.ktor.client.rpcConfig
import kotlinx.rpc.krpc.serialization.json.json
import kotlinx.rpc.withService

/**
 * Supplies an [InstanceService] kotlinx.rpc proxy for pre-authentication server
 * verification.
 *
 * Unlike the post-login RPC channels (auth, invite, …) this
 * one does **not** reuse [ApiClientFactory] or read [ServerConfig]: verification
 * runs before any server URL is saved, and `ApiClientFactory.getClient()` errors
 * when no active URL is configured. So the proxy is built per call against an
 * explicit URL the caller is trying to verify — a transient connection, not a
 * cached one.
 *
 * An interface so the repository depends on a seam that fakes in tests;
 * [KtorInstanceRpcFactory] is the production implementation over WebSocket RPC.
 */
internal interface InstanceRpcFactory {
    /**
     * Connect to [wsBaseUrl] (a `ws://`/`wss://` origin) and fetch its
     * [com.calypsan.listenup.api.dto.ServerInfo] over the public RPC surface.
     *
     * @param wsBaseUrl WebSocket-scheme origin, e.g. `wss://library.example.com`.
     * @return the service's typed [com.calypsan.listenup.api.result.AppResult];
     *   transport failures surface as a thrown exception the caller catches.
     */
    suspend fun getServerInfo(
        wsBaseUrl: String,
    ): com.calypsan.listenup.api.result.AppResult<com.calypsan.listenup.api.dto.ServerInfo>
}

/**
 * Production [InstanceRpcFactory]: opens a fresh kRPC WebSocket to the explicit
 * URL's `/api/rpc/public` mount, fetches once, and lets the client be GC'd. No
 * caching — verification is a one-shot probe against a URL that may not pan out.
 *
 * Wire serialization is the contract-layer [contractJson] — one wire format,
 * the same the server registers with.
 *
 * Bounding the probe is **mandatory** here: it runs behind the server-picker spinner,
 * so it can never hang. Two layers cover the two failure shapes:
 *  - [HttpTimeout] bounds the **connect/upgrade** (an unroutable LAN address — a host
 *    advertising its docker-bridge/VPN address, or a server that moved IP).
 *  - [withTimeoutOrNull] bounds the **whole operation**, including a kRPC call that
 *    stalls *after* a successful WebSocket upgrade (`101`) — which `HttpTimeout` does
 *    not catch, and which otherwise hangs the spinner indefinitely.
 *
 * Timeouts are constructor-injectable so the hang is regression-tested against a
 * black-hole socket.
 */
internal class KtorInstanceRpcFactory(
    private val connectTimeoutMillis: Long = DEFAULT_CONNECT_TIMEOUT_MS,
    private val requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MS,
    private val socketTimeoutMillis: Long = DEFAULT_SOCKET_TIMEOUT_MS,
) : InstanceRpcFactory {
    override suspend fun getServerInfo(
        wsBaseUrl: String,
    ): com.calypsan.listenup.api.result.AppResult<com.calypsan.listenup.api.dto.ServerInfo> {
        val client =
            HttpClient {
                installKrpc()
                install(WebSockets)
                install(HttpTimeout) {
                    connectTimeoutMillis = this@KtorInstanceRpcFactory.connectTimeoutMillis
                    requestTimeoutMillis = this@KtorInstanceRpcFactory.requestTimeoutMillis
                    socketTimeoutMillis = this@KtorInstanceRpcFactory.socketTimeoutMillis
                }
            }
        return try {
            withTimeoutOrNull(requestTimeoutMillis) {
                client
                    .rpc("${wsBaseUrl.trimEnd('/')}/api/rpc/public") {
                        rpcConfig { serialization { json(contractJson) } }
                    }.withService<InstanceService>()
                    .getServerInfo()
            } ?: AppResult.Failure(
                TransportError.Timeout(
                    debugInfo = "getServerInfo exceeded ${requestTimeoutMillis}ms (connect or post-upgrade RPC stall)",
                ),
            )
        } finally {
            client.close()
        }
    }

    private companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 5_000L
        const val DEFAULT_REQUEST_TIMEOUT_MS = 12_000L
        const val DEFAULT_SOCKET_TIMEOUT_MS = 12_000L
    }
}
