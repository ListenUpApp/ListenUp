package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.AdminSettingsService
import com.calypsan.listenup.client.domain.repository.ServerConfig
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.withService

/**
 * Supplies the [AdminSettingsService] kotlinx.rpc proxy backing the admin server-identity
 * settings surface (server name + remote URL). An interface so repositories depend
 * on a seam that fakes in tests; [KtorAdminSettingsRpcFactory] is the production implementation
 * over the bearer-gated `/api/rpc/authed` WebSocket.
 *
 * Mirrors [AdminUserRpcFactory] — the established RPC-factory shape.
 */
internal interface AdminSettingsRpcFactory {
    /** Returns the cached [AdminSettingsService] proxy, connecting on first use. */
    suspend fun get(): AdminSettingsService

    /** Drop the cached proxy and the RPC-flavored HttpClient. */
    suspend fun invalidate()
}

/**
 * Production [AdminSettingsRpcFactory]: delegates the connection lifecycle to
 * [RpcProxyCache], supplying the `/api/rpc/authed` mount and the reified
 * [AdminSettingsService] binding.
 */
internal class KtorAdminSettingsRpcFactory(
    apiClientFactory: ApiClientFactory,
    serverConfig: ServerConfig,
) : AdminSettingsRpcFactory,
    RemoteCache {
    private val cache =
        RpcProxyCache(apiClientFactory, serverConfig) { client, baseUrl ->
            client.rpc("$baseUrl/api/rpc/authed").withService<AdminSettingsService>()
        }

    override suspend fun get(): AdminSettingsService = cache.get()

    override suspend fun invalidate() = cache.invalidate()
}
