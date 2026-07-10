package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.PushService
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.ServerConfig
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.withService

/**
 * Supplies the [PushService] kotlinx.rpc proxy that backs device push-token
 * registration and diagnostics.
 *
 * An interface so repositories and ViewModels depend on a seam that can be faked
 * in tests — [KtorPushRpcFactory] is the production implementation over
 * WebSocket RPC. Mirrors [ShelfRpcFactory] — the established RPC factory precedent.
 */
internal interface PushRpcFactory {
    /** Returns the cached [PushService] proxy, connecting on first use. */
    suspend fun get(): PushService

    /**
     * Run [block] against the [PushService] proxy through the bounded, self-healing recovery
     * engine, folding the outcome into an [AppResult]. The canonical mutation path — a dead
     * socket heals invisibly, a surviving fault surfaces typed, a business failure passes
     * through untouched.
     */
    suspend fun <T> callResult(block: suspend (PushService) -> AppResult<T>): AppResult<T>

    /** Drop the cached proxy and the RPC-flavored HttpClient. */
    suspend fun invalidate()
}

/**
 * Production [PushRpcFactory]: delegates the connection lifecycle to
 * [RpcProxyCache], supplying the `/api/rpc/authed` mount and the reified
 * [PushService] binding.
 */
internal class KtorPushRpcFactory(
    apiClientFactory: ApiClientFactory,
    serverConfig: ServerConfig,
    authRecovery: RpcAuthRecovery = RpcAuthRecovery.None,
) : PushRpcFactory,
    RemoteCache {
    private val cache =
        RpcProxyCache(apiClientFactory, serverConfig, authRecovery) { client, baseUrl ->
            client.rpc("$baseUrl/api/rpc/authed").withService<PushService>()
        }

    override suspend fun get(): PushService = cache.get()

    override suspend fun <T> callResult(block: suspend (PushService) -> AppResult<T>): AppResult<T> =
        cache.rpcCall(block = block)

    override suspend fun invalidate() = cache.invalidate()
}
