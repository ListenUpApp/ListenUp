package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.ScannerService
import com.calypsan.listenup.client.domain.repository.ServerConfig
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.withService

/**
 * Supplies the [ScannerService] kotlinx.rpc proxy used to observe live scan
 * progress (`observeProgress(): Flow<RpcEvent<ScanEvent>>`).
 *
 * [ScannerService] is mounted on the **authed** RPC surface (`/api/rpc/authed`),
 * so the shared [ApiClientFactory] attaches the bearer token to the WebSocket
 * upgrade automatically — `/api/rpc/authed` is deliberately not in the auth-exempt
 * prefix list. The proxy is cached and reused; the underlying [HttpClient] comes
 * from [ApiClientFactory] (same one the rest of the app uses), so [invalidate]
 * drops the cached proxy when that client is recycled.
 */
internal interface ScannerRpcFactory {
    /** Returns the cached [ScannerService] proxy, connecting on first use. */
    suspend fun get(): ScannerService

    /** Drop the cached proxy and RPC-flavored client. */
    suspend fun invalidate()
}

/**
 * Production [ScannerRpcFactory]: delegates the connection lifecycle to
 * [RpcProxyCache], supplying the `/api/rpc/authed` mount and the reified
 * [ScannerService] binding.
 */
internal class KtorScannerRpcFactory(
    apiClientFactory: ApiClientFactory,
    serverConfig: ServerConfig,
) : ScannerRpcFactory,
    RemoteCache {
    private val cache =
        RpcProxyCache(apiClientFactory, serverConfig) { client, baseUrl ->
            client.rpc("$baseUrl/api/rpc/authed").withService<ScannerService>()
        }

    override suspend fun get(): ScannerService = cache.get()

    override suspend fun invalidate() = cache.invalidate()
}
