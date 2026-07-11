package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.ProfileService
import com.calypsan.listenup.client.domain.repository.ServerConfig
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.withService

/**
 * Supplies the [ProfileService] kotlinx.rpc proxy that backs the authenticated
 * user's own profile — read and update operations.
 *
 * An interface so repositories depend on a seam that can be faked in tests —
 * [KtorProfileRpcFactory] is the production implementation over WebSocket RPC.
 * Follows the established authed-RPC factory precedent.
 */
internal interface ProfileRpcFactory {
    /** Returns the cached [ProfileService] proxy, connecting on first use. */
    suspend fun get(): ProfileService

    /** Drop the cached proxy and the RPC-flavored HttpClient. */
    suspend fun invalidate()
}

/**
 * Production [ProfileRpcFactory]: delegates the connection lifecycle to
 * [RpcProxyCache], supplying the `/api/rpc/authed` mount and the reified
 * [ProfileService] binding.
 */
internal class KtorProfileRpcFactory(
    apiClientFactory: ApiClientFactory,
    serverConfig: ServerConfig,
) : ProfileRpcFactory,
    RemoteCache {
    private val cache =
        RpcProxyCache(apiClientFactory, serverConfig) { client, baseUrl ->
            client.rpc("$baseUrl/api/rpc/authed").withService<ProfileService>()
        }

    override suspend fun get(): ProfileService = cache.get()

    override suspend fun invalidate() = cache.invalidate()
}
