package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.BookService
import com.calypsan.listenup.client.domain.repository.ServerConfig
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.withService

/**
 * Supplies the [BookService] kotlinx.rpc proxy that backs on-demand book
 * fetches and server-side FTS search.
 *
 * An interface so repositories depend on a seam that fakes/mocks in tests —
 * [KtorBookRpcFactory] is the production implementation over WebSocket RPC.
 *
 * **Wiring status:** fully functional end-to-end. The `:server` module
 * registers `BookService` on its bearer-gated `/api/rpc/authed` surface
 * (landed in T28.5), so the client proxy connects and calls succeed.
 */
internal interface BookRpcFactory {
    /** Returns the cached [BookService] proxy, connecting on first use. */
    suspend fun bookService(): BookService

    /** Drop the cached proxy and the RPC-flavored HttpClient. */
    suspend fun invalidate()
}

/**
 * Production [BookRpcFactory]: delegates the connection lifecycle to
 * [RpcProxyCache], supplying the `/api/rpc/authed` mount and the reified
 * [BookService] binding.
 */
internal class KtorBookRpcFactory(
    apiClientFactory: ApiClientFactory,
    serverConfig: ServerConfig,
) : BookRpcFactory,
    RemoteCache {
    private val cache =
        RpcProxyCache(apiClientFactory, serverConfig) { client, baseUrl ->
            client.rpc("$baseUrl/api/rpc/authed").withService<BookService>()
        }

    override suspend fun bookService(): BookService = cache.get()

    override suspend fun invalidate() = cache.invalidate()
}
