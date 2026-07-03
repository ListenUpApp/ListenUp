package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.SeriesService
import com.calypsan.listenup.client.domain.repository.ServerConfig
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.withService

/**
 * Supplies the [SeriesService] kotlinx.rpc proxy that backs on-demand series
 * fetches (cache-miss fallback from [SeriesRepositoryImpl]).
 *
 * An interface so repositories depend on a seam that fakes/mocks in tests —
 * [KtorSeriesRpcFactory] is the production implementation over WebSocket RPC.
 *
 * **Wiring status:** fully functional end-to-end. The `:server` module registers
 * `SeriesService` on its bearer-gated `/api/rpc/authed` surface (landed in
 * Books-B2), so the client proxy connects and calls succeed.
 */
internal interface SeriesRpcFactory {
    /** Returns the cached [SeriesService] proxy, connecting on first use. */
    suspend fun seriesService(): SeriesService

    /** Drop the cached proxy and the RPC-flavored HttpClient. */
    suspend fun invalidate()
}

/**
 * Production [SeriesRpcFactory]: delegates the connection lifecycle to
 * [RpcProxyCache], supplying the `/api/rpc/authed` mount and the reified
 * [SeriesService] binding.
 */
internal class KtorSeriesRpcFactory(
    apiClientFactory: ApiClientFactory,
    serverConfig: ServerConfig,
) : SeriesRpcFactory,
    RemoteCache {
    private val cache =
        RpcProxyCache(apiClientFactory, serverConfig) { client, baseUrl ->
            client.rpc("$baseUrl/api/rpc/authed").withService<SeriesService>()
        }

    override suspend fun seriesService(): SeriesService = cache.get()

    override suspend fun invalidate() = cache.invalidate()
}
