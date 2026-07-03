package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.ShelfService
import com.calypsan.listenup.client.domain.repository.ServerConfig
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.withService

/**
 * Supplies the [ShelfService] kotlinx.rpc proxy that backs shelf lifecycle,
 * membership, and discovery operations.
 *
 * An interface so repositories and ViewModels depend on a seam that can be faked
 * in tests — [KtorShelfRpcFactory] is the production implementation over
 * WebSocket RPC. Mirrors [CollectionRpcFactory] — the established RPC factory precedent.
 */
internal interface ShelfRpcFactory {
    /** Returns the cached [ShelfService] proxy, connecting on first use. */
    suspend fun get(): ShelfService

    /** Drop the cached proxy and the RPC-flavored HttpClient. */
    suspend fun invalidate()
}

/**
 * Production [ShelfRpcFactory]: delegates the connection lifecycle to
 * [RpcProxyCache], supplying the `/api/rpc/authed` mount and the reified
 * [ShelfService] binding.
 */
internal class KtorShelfRpcFactory(
    apiClientFactory: ApiClientFactory,
    serverConfig: ServerConfig,
) : ShelfRpcFactory,
    RemoteCache {
    private val cache =
        RpcProxyCache(apiClientFactory, serverConfig) { client, baseUrl ->
            client.rpc("$baseUrl/api/rpc/authed").withService<ShelfService>()
        }

    override suspend fun get(): ShelfService = cache.get()

    override suspend fun invalidate() = cache.invalidate()
}
