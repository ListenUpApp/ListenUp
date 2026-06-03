package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.InstanceService
import com.calypsan.listenup.api.contractJson
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.ktor.client.rpcConfig
import kotlinx.rpc.krpc.serialization.json.json
import kotlinx.rpc.withService

/**
 * Supplies an [InstanceService] kotlinx.rpc proxy for pre-authentication server
 * verification.
 *
 * Unlike the post-login factories ([AuthRpcFactory], [BookRpcFactory], …) this
 * one does **not** reuse [ApiClientFactory] or read [ServerConfig]: verification
 * runs before any server URL is saved, and `ApiClientFactory.getClient()` errors
 * when no active URL is configured. So the proxy is built per call against an
 * explicit URL the caller is trying to verify — a transient connection, not a
 * cached one.
 *
 * An interface so the repository depends on a seam that fakes in tests;
 * [KtorInstanceRpcFactory] is the production implementation over WebSocket RPC.
 */
interface InstanceRpcFactory {
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
 */
class KtorInstanceRpcFactory : InstanceRpcFactory {
    override suspend fun getServerInfo(
        wsBaseUrl: String,
    ): com.calypsan.listenup.api.result.AppResult<com.calypsan.listenup.api.dto.ServerInfo> {
        val client =
            HttpClient {
                installKrpc()
                install(WebSockets)
            }
        return try {
            client
                .rpc("${wsBaseUrl.trimEnd('/')}/api/rpc/public") {
                    rpcConfig { serialization { json(contractJson) } }
                }.withService<InstanceService>()
                .getServerInfo()
        } finally {
            client.close()
        }
    }
}
