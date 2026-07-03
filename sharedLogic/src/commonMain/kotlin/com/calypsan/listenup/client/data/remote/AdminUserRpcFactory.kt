package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.AdminUserService
import com.calypsan.listenup.client.domain.repository.ServerConfig
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.withService

/**
 * Supplies the [AdminUserService] kotlinx.rpc proxy backing the admin user-management
 * surface (roster, approval queue, role/permission edits, soft-delete). An interface so
 * repositories depend on a seam that fakes in tests; [KtorAdminUserRpcFactory] is the
 * production implementation over the bearer-gated `/api/rpc/authed` WebSocket.
 *
 * Mirrors [LibraryAdminRpcFactory] — the established RPC-factory shape.
 */
internal interface AdminUserRpcFactory {
    /** Returns the cached [AdminUserService] proxy, connecting on first use. */
    suspend fun get(): AdminUserService

    /** Drop the cached proxy and the RPC-flavored HttpClient. */
    suspend fun invalidate()
}

/**
 * Production [AdminUserRpcFactory]: delegates the connection lifecycle to
 * [RpcProxyCache], supplying the `/api/rpc/authed` mount and the reified
 * [AdminUserService] binding.
 */
internal class KtorAdminUserRpcFactory(
    apiClientFactory: ApiClientFactory,
    serverConfig: ServerConfig,
) : AdminUserRpcFactory,
    RemoteCache {
    private val cache =
        RpcProxyCache(apiClientFactory, serverConfig) { client, baseUrl ->
            client.rpc("$baseUrl/api/rpc/authed").withService<AdminUserService>()
        }

    override suspend fun get(): AdminUserService = cache.get()

    override suspend fun invalidate() = cache.invalidate()
}
