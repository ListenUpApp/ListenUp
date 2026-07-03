package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.ImportService
import com.calypsan.listenup.client.domain.repository.ServerConfig
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.withService

/**
 * Supplies the [ImportService] kotlinx.rpc proxy that backs admin Audiobookshelf
 * import operations.
 *
 * An interface so repositories depend on a seam that can be faked in tests —
 * [KtorImportRpcFactory] is the production implementation over WebSocket RPC.
 * Mirrors [BackupRpcFactory] — the established admin RPC factory precedent.
 */
internal interface ImportRpcFactory {
    /** Returns the cached [ImportService] proxy, connecting on first use. */
    suspend fun get(): ImportService

    /** Drop the cached proxy and the RPC-flavored HttpClient. */
    suspend fun invalidate()
}

/**
 * Production [ImportRpcFactory]: delegates the connection lifecycle to
 * [RpcProxyCache], supplying the `/api/rpc/authed` mount and the reified
 * [ImportService] binding.
 */
internal class KtorImportRpcFactory(
    apiClientFactory: ApiClientFactory,
    serverConfig: ServerConfig,
) : ImportRpcFactory,
    RemoteCache {
    private val cache =
        RpcProxyCache(apiClientFactory, serverConfig) { client, baseUrl ->
            client.rpc("$baseUrl/api/rpc/authed").withService<ImportService>()
        }

    override suspend fun get(): ImportService = cache.get()

    override suspend fun invalidate() = cache.invalidate()
}
