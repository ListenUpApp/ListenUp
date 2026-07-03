package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.GenreService
import com.calypsan.listenup.client.domain.repository.ServerConfig
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.withService

/**
 * Supplies the [GenreService] kotlinx.rpc proxy that backs curator mutations
 * (`createGenre`/`updateGenre`/`deleteGenre`/`moveGenre`/`mergeGenres`).
 *
 * Tree reads (list, getById, getChildren) come from the client Room mirror,
 * which the sync engine populates via [com.calypsan.listenup.client.data.sync.domains.genresDomain] —
 * so the RPC factory is mutation-shaped, not read-shaped.
 *
 * An interface so repositories depend on a seam that fakes/mocks in tests —
 * [KtorGenreRpcFactory] is the production implementation over WebSocket RPC.
 */
internal interface GenreRpcFactory {
    /** Returns the cached [GenreService] proxy, connecting on first use. */
    suspend fun genreService(): GenreService

    /** Drop the cached proxy and the RPC-flavored HttpClient. */
    suspend fun invalidate()
}

/**
 * Production [GenreRpcFactory]: delegates the connection lifecycle to
 * [RpcProxyCache], supplying the `/api/rpc/authed` mount and the reified
 * [GenreService] binding.
 */
internal class KtorGenreRpcFactory(
    apiClientFactory: ApiClientFactory,
    serverConfig: ServerConfig,
) : GenreRpcFactory,
    RemoteCache {
    private val cache =
        RpcProxyCache(apiClientFactory, serverConfig) { client, baseUrl ->
            client.rpc("$baseUrl/api/rpc/authed").withService<GenreService>()
        }

    override suspend fun genreService(): GenreService = cache.get()

    override suspend fun invalidate() = cache.invalidate()
}
