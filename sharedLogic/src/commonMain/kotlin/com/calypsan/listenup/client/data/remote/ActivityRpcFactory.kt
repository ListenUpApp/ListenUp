package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.ActivityService
import com.calypsan.listenup.client.domain.repository.ServerConfig
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.withService

/**
 * Supplies the [ActivityService] kotlinx.rpc proxy that backs social presence,
 * activity, and discovery operations.
 *
 * An interface so repositories and ViewModels depend on a seam that can be faked
 * in tests — [KtorActivityRpcFactory] is the production implementation over
 * WebSocket RPC. Mirrors the established RPC factory precedent.
 */
internal interface ActivityRpcFactory {
    /** Returns the cached [ActivityService] proxy, connecting on first use. */
    suspend fun get(): ActivityService

    /** Drop the cached proxy and the RPC-flavored HttpClient. */
    suspend fun invalidate()
}

/**
 * Production [ActivityRpcFactory]: delegates the connection lifecycle to
 * [RpcProxyCache], supplying the `/api/rpc/authed` mount and the reified
 * [ActivityService] binding.
 */
internal class KtorActivityRpcFactory(
    apiClientFactory: ApiClientFactory,
    serverConfig: ServerConfig,
) : ActivityRpcFactory,
    RemoteCache {
    private val cache =
        RpcProxyCache(apiClientFactory, serverConfig) { client, baseUrl ->
            client.rpc("$baseUrl/api/rpc/authed").withService<ActivityService>()
        }

    override suspend fun get(): ActivityService = cache.get()

    override suspend fun invalidate() = cache.invalidate()
}
