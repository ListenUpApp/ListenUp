package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.SocialService
import com.calypsan.listenup.client.domain.repository.ServerConfig
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.withService

/**
 * Supplies the [SocialService] kotlinx.rpc proxy that backs social presence,
 * activity, and discovery operations.
 *
 * An interface so repositories and ViewModels depend on a seam that can be faked
 * in tests — [KtorSocialRpcFactory] is the production implementation over
 * WebSocket RPC. Mirrors the established RPC factory precedent.
 */
internal interface SocialRpcFactory {
    /** Returns the cached [SocialService] proxy, connecting on first use. */
    suspend fun get(): SocialService

    /** Drop the cached proxy and the RPC-flavored HttpClient. */
    suspend fun invalidate()
}

/**
 * Production [SocialRpcFactory]: delegates the connection lifecycle to
 * [RpcProxyCache], supplying the `/api/rpc/authed` mount and the reified
 * [SocialService] binding.
 */
internal class KtorSocialRpcFactory(
    apiClientFactory: ApiClientFactory,
    serverConfig: ServerConfig,
) : SocialRpcFactory,
    RemoteCache {
    private val cache =
        RpcProxyCache(apiClientFactory, serverConfig) { client, baseUrl ->
            client.rpc("$baseUrl/api/rpc/authed").withService<SocialService>()
        }

    override suspend fun get(): SocialService = cache.get()

    override suspend fun invalidate() = cache.invalidate()
}
