package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.MoodService
import com.calypsan.listenup.client.domain.repository.ServerConfig
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.withService

/**
 * Supplies the [MoodService] kotlinx.rpc proxy that backs mood lifecycle and observation
 * operations — [MoodService.addMoodToBook], [MoodService.removeMoodFromBook], etc.
 *
 * An interface so repositories and ViewModels depend on a seam that can be faked in tests —
 * [KtorMoodRpcFactory] is the production implementation over WebSocket RPC.
 *
 * Mirrors [TagRpcFactory] — the established precedent for RPC factory seams.
 */
internal interface MoodRpcFactory {
    /** Returns the cached [MoodService] proxy, connecting on first use. */
    suspend fun get(): MoodService

    /** Drop the cached proxy and the RPC-flavored HttpClient. */
    suspend fun invalidate()
}

/**
 * Production [MoodRpcFactory]: delegates the connection lifecycle to
 * [RpcProxyCache], supplying the `/api/rpc/authed` mount and the reified
 * [MoodService] binding.
 */
internal class KtorMoodRpcFactory(
    apiClientFactory: ApiClientFactory,
    serverConfig: ServerConfig,
) : MoodRpcFactory,
    RemoteCache {
    private val cache =
        RpcProxyCache(apiClientFactory, serverConfig) { client, baseUrl ->
            client.rpc("$baseUrl/api/rpc/authed").withService<MoodService>()
        }

    override suspend fun get(): MoodService = cache.get()

    override suspend fun invalidate() = cache.invalidate()
}
