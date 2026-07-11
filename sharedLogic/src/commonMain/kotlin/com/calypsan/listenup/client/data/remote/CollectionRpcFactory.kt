package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.CollectionService
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.ServerConfig
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.withService

/**
 * Supplies the [CollectionService] kotlinx.rpc proxy that backs collection
 * lifecycle, membership, and sharing operations.
 *
 * An interface so repositories and ViewModels depend on a seam that can be faked
 * in tests — [KtorCollectionRpcFactory] is the production implementation over
 * WebSocket RPC. Mirrors the established RPC factory precedent.
 */
internal interface CollectionRpcFactory {
    /** Returns the cached [CollectionService] proxy, connecting on first use. */
    suspend fun get(): CollectionService

    /**
     * Run [block] against the [CollectionService] proxy through the bounded, self-healing recovery
     * engine, folding the outcome into an [AppResult]. The canonical mutation/query path — a dead
     * socket heals invisibly, a surviving fault surfaces typed, a business failure passes through
     * untouched.
     */
    suspend fun <T> callResult(block: suspend (CollectionService) -> AppResult<T>): AppResult<T>

    /** Drop the cached proxy and the RPC-flavored HttpClient. */
    suspend fun invalidate()
}

/**
 * Production [CollectionRpcFactory]: delegates the connection lifecycle to
 * [RpcProxyCache], supplying the `/api/rpc/authed` mount and the reified
 * [CollectionService] binding.
 */
internal class KtorCollectionRpcFactory(
    apiClientFactory: ApiClientFactory,
    serverConfig: ServerConfig,
    authRecovery: RpcAuthRecovery = RpcAuthRecovery.None,
) : CollectionRpcFactory,
    RemoteCache {
    private val cache =
        RpcProxyCache(apiClientFactory, serverConfig, authRecovery) { client, baseUrl ->
            client.rpc("$baseUrl/api/rpc/authed").withService<CollectionService>()
        }

    override suspend fun get(): CollectionService = cache.get()

    override suspend fun <T> callResult(block: suspend (CollectionService) -> AppResult<T>): AppResult<T> =
        cache.rpcCall(block = block)

    override suspend fun invalidate() = cache.invalidate()
}
