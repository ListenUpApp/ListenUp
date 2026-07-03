package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.UserPreferencesService
import com.calypsan.listenup.client.domain.repository.ServerConfig
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.withService

/**
 * Supplies the [UserPreferencesService] kotlinx.rpc proxy backing the authenticated user's
 * cross-device playback preferences — read and update operations.
 *
 * An interface so repositories depend on a seam that can be faked in tests —
 * [KtorUserPreferencesRpcFactory] is the production implementation over WebSocket RPC.
 * Mirrors [ProfileRpcFactory], the established authed-RPC factory precedent.
 */
internal interface UserPreferencesRpcFactory {
    /** Returns the cached [UserPreferencesService] proxy, connecting on first use. */
    suspend fun get(): UserPreferencesService

    /** Drop the cached proxy and the RPC-flavored HttpClient. */
    suspend fun invalidate()
}

/**
 * Production [UserPreferencesRpcFactory]: delegates the connection lifecycle to
 * [RpcProxyCache], supplying the `/api/rpc/authed` mount and the reified
 * [UserPreferencesService] binding.
 */
internal class KtorUserPreferencesRpcFactory(
    apiClientFactory: ApiClientFactory,
    serverConfig: ServerConfig,
) : UserPreferencesRpcFactory,
    RemoteCache {
    private val cache =
        RpcProxyCache(apiClientFactory, serverConfig) { client, baseUrl ->
            client.rpc("$baseUrl/api/rpc/authed").withService<UserPreferencesService>()
        }

    override suspend fun get(): UserPreferencesService = cache.get()

    override suspend fun invalidate() = cache.invalidate()
}
