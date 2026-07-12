package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.CampfireService
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.ServerConfig
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.withService

/**
 * Supplies the [CampfireService] kotlinx.rpc proxy that backs co-listening sessions
 * (create/join/leave, playback commands, chat, reactions, and the live
 * `observeSession()` downlink).
 *
 * An interface so [com.calypsan.listenup.client.campfire.CampfireRpcTransport] depends
 * on a seam that can be faked in tests — [KtorCampfireRpcFactory] is the production
 * implementation over WebSocket RPC. Mirrors [PushRpcFactory] — the established RPC
 * factory precedent.
 */
internal interface CampfireRpcFactory {
    /** Returns the cached [CampfireService] proxy, connecting on first use. */
    suspend fun get(): CampfireService

    /**
     * Run [block] against the [CampfireService] proxy through the bounded, self-healing recovery
     * engine, folding the outcome into an [AppResult]. The canonical mutation path — a dead
     * socket heals invisibly, a surviving fault surfaces typed, a business failure passes
     * through untouched.
     */
    suspend fun <T> callResult(block: suspend (CampfireService) -> AppResult<T>): AppResult<T>

    /** Drop the cached proxy and the RPC-flavored HttpClient. */
    suspend fun invalidate()
}

/**
 * Production [CampfireRpcFactory]: delegates the connection lifecycle to
 * [RpcProxyCache], supplying the `/api/rpc/authed` mount and the reified
 * [CampfireService] binding.
 */
internal class KtorCampfireRpcFactory(
    apiClientFactory: ApiClientFactory,
    serverConfig: ServerConfig,
    authRecovery: RpcAuthRecovery = RpcAuthRecovery.None,
) : CampfireRpcFactory,
    RemoteCache {
    private val cache =
        RpcProxyCache(apiClientFactory, serverConfig, authRecovery) { client, baseUrl ->
            client.rpc("$baseUrl/api/rpc/authed").withService<CampfireService>()
        }

    override suspend fun get(): CampfireService = cache.get()

    override suspend fun <T> callResult(block: suspend (CampfireService) -> AppResult<T>): AppResult<T> =
        cache.rpcCall(block = block)

    override suspend fun invalidate() = cache.invalidate()
}
