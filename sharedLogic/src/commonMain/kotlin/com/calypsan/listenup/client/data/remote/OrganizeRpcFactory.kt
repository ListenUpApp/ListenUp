package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.OrganizeService
import com.calypsan.listenup.client.domain.repository.ServerConfig
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.withService

/**
 * Supplies the [OrganizeService] kotlinx.rpc proxy backing the admin file-organizer settings
 * surface. An interface so repositories depend on a seam that fakes in tests;
 * [KtorOrganizeRpcFactory] is the production implementation over the bearer-gated
 * `/api/rpc/authed` WebSocket.
 *
 * Mirrors [AdminSettingsRpcFactory] — the established RPC-factory shape.
 */
internal interface OrganizeRpcFactory {
    /** Returns the cached [OrganizeService] proxy, connecting on first use. */
    suspend fun get(): OrganizeService

    /** Drop the cached proxy and the RPC-flavored HttpClient. */
    suspend fun invalidate()
}

/**
 * Production [OrganizeRpcFactory]: delegates the connection lifecycle to [RpcProxyCache],
 * supplying the `/api/rpc/authed` mount and the reified [OrganizeService] binding.
 */
internal class KtorOrganizeRpcFactory(
    apiClientFactory: ApiClientFactory,
    serverConfig: ServerConfig,
) : OrganizeRpcFactory,
    RemoteCache {
    private val cache =
        RpcProxyCache(apiClientFactory, serverConfig) { client, baseUrl ->
            client.rpc("$baseUrl/api/rpc/authed").withService<OrganizeService>()
        }

    override suspend fun get(): OrganizeService = cache.get()

    override suspend fun invalidate() = cache.invalidate()
}
