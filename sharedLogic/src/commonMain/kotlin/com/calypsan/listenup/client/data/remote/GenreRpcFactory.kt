package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.GenreService
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.client.domain.repository.ServerConfig
import io.ktor.client.HttpClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.serialization.json.json
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
 * Mounts the [GenreService] proxy over `/api/rpc/authed` — the bearer-gated
 * RPC surface. Mirrors [KtorContributorRpcFactory] / [KtorBookRpcFactory] in
 * structure; the first call opens the WebSocket lazily and subsequent calls
 * reuse the proxy.
 *
 * Wire serialization is the contract-layer [contractJson] — one wire format,
 * two transports.
 */
internal class KtorGenreRpcFactory(
    private val apiClientFactory: ApiClientFactory,
    private val serverConfig: ServerConfig,
) : GenreRpcFactory,
    RemoteCache {
    private val mutex = Mutex()
    private var cachedRpcClient: HttpClient? = null
    private var cachedService: GenreService? = null

    override suspend fun genreService(): GenreService =
        mutex.withLock {
            cachedService ?: connect().also { cachedService = it }
        }

    override suspend fun invalidate() {
        mutex.withLock {
            cachedService = null
            cachedRpcClient = null
        }
    }

    private suspend fun connect(): GenreService {
        val baseUrl = rpcBaseUrl()
        return rpcClient().rpc("$baseUrl/api/rpc/authed").withService<GenreService>()
    }

    private suspend fun rpcClient(): HttpClient =
        cachedRpcClient ?: apiClientFactory
            .getClient()
            .config {
                installKrpc {
                    serialization { json(contractJson) }
                }
            }.also { cachedRpcClient = it }

    private suspend fun rpcBaseUrl(): String {
        val httpUrl =
            serverConfig.getActiveUrl()?.value
                ?: throw ServerUrlNotConfiguredException()
        return toWebSocketScheme(httpUrl)
    }
}
