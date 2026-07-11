package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.ReadingOrderService
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.ServerConfig
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.withService

/**
 * Supplies the [ReadingOrderService] kotlinx.rpc proxy that backs reading-order
 * lifecycle, membership, follow-state, and discovery operations.
 *
 * An interface so repositories and the outbox sender depend on a seam that can be
 * faked in tests — [KtorReadingOrderRpcFactory] is the production implementation
 * over WebSocket RPC. Mirrors [ShelfRpcFactory].
 */
internal interface ReadingOrderRpcFactory {
    /** Returns the cached [ReadingOrderService] proxy, connecting on first use. */
    suspend fun get(): ReadingOrderService

    /**
     * Run [block] against the [ReadingOrderService] proxy through the bounded,
     * self-healing recovery engine, folding the outcome into an [AppResult]. The
     * canonical mutation/query path — a dead socket heals invisibly, a surviving
     * fault surfaces typed, a business failure passes through untouched.
     */
    suspend fun <T> callResult(block: suspend (ReadingOrderService) -> AppResult<T>): AppResult<T>

    /** Drop the cached proxy and the RPC-flavored HttpClient. */
    suspend fun invalidate()
}

/**
 * Production [ReadingOrderRpcFactory]: delegates the connection lifecycle to
 * [RpcProxyCache], supplying the `/api/rpc/authed` mount and the reified
 * [ReadingOrderService] binding.
 */
internal class KtorReadingOrderRpcFactory(
    apiClientFactory: ApiClientFactory,
    serverConfig: ServerConfig,
    authRecovery: RpcAuthRecovery = RpcAuthRecovery.None,
) : ReadingOrderRpcFactory,
    RemoteCache {
    private val cache =
        RpcProxyCache(apiClientFactory, serverConfig, authRecovery) { client, baseUrl ->
            client.rpc("$baseUrl/api/rpc/authed").withService<ReadingOrderService>()
        }

    override suspend fun get(): ReadingOrderService = cache.get()

    override suspend fun <T> callResult(block: suspend (ReadingOrderService) -> AppResult<T>): AppResult<T> =
        cache.rpcCall(block = block)

    override suspend fun invalidate() = cache.invalidate()
}
