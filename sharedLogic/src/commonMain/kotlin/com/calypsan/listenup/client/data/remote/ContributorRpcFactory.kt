package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.ContributorService
import com.calypsan.listenup.client.domain.repository.ServerConfig
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.withService

/**
 * Supplies the [ContributorService] kotlinx.rpc proxy that backs on-demand
 * contributor fetches (cache-miss fallback from [ContributorRepositoryImpl]).
 *
 * An interface so repositories depend on a seam that fakes/mocks in tests —
 * [KtorContributorRpcFactory] is the production implementation over WebSocket RPC.
 *
 * **Wiring status:** fully functional end-to-end. The `:server` module registers
 * `ContributorService` on its bearer-gated `/api/rpc/authed` surface (landed in
 * Books-B2), so the client proxy connects and calls succeed.
 */
internal interface ContributorRpcFactory {
    /** Returns the cached [ContributorService] proxy, connecting on first use. */
    suspend fun contributorService(): ContributorService

    /** Drop the cached proxy and the RPC-flavored HttpClient. */
    suspend fun invalidate()
}

/**
 * Production [ContributorRpcFactory]: delegates the connection lifecycle to
 * [RpcProxyCache], supplying the `/api/rpc/authed` mount and the reified
 * [ContributorService] binding.
 */
internal class KtorContributorRpcFactory(
    apiClientFactory: ApiClientFactory,
    serverConfig: ServerConfig,
) : ContributorRpcFactory,
    RemoteCache {
    private val cache =
        RpcProxyCache(apiClientFactory, serverConfig) { client, baseUrl ->
            client.rpc("$baseUrl/api/rpc/authed").withService<ContributorService>()
        }

    override suspend fun contributorService(): ContributorService = cache.get()

    override suspend fun invalidate() = cache.invalidate()
}
