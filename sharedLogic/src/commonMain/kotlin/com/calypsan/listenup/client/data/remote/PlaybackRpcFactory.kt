package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.PlaybackService
import com.calypsan.listenup.client.domain.repository.ServerConfig
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.withService

/**
 * Supplies the [PlaybackService] kotlinx.rpc proxy that backs playback preparation
 * and position recording (the client write path).
 *
 * An interface so repositories depend on a seam that fakes/mocks in tests —
 * [KtorPlaybackRpcFactory] is the production implementation over WebSocket RPC.
 *
 * **Wiring status:** fully functional end-to-end. The `:server` module registers
 * `PlaybackService` on its bearer-gated `/api/rpc/authed` surface, so the client
 * proxy connects and calls succeed.
 */
interface PlaybackRpcFactory {
    /** Returns the cached [PlaybackService] proxy, connecting on first use. */
    suspend fun playbackService(): PlaybackService

    /** Drop the cached proxy and the RPC-flavored HttpClient. */
    suspend fun invalidate()
}

/**
 * Production [PlaybackRpcFactory]: delegates the connection lifecycle to
 * [RpcProxyCache], supplying the `/api/rpc/authed` mount and the reified
 * [PlaybackService] binding.
 */
internal class KtorPlaybackRpcFactory(
    apiClientFactory: ApiClientFactory,
    serverConfig: ServerConfig,
) : PlaybackRpcFactory,
    RemoteCache {
    private val cache =
        RpcProxyCache(apiClientFactory, serverConfig) { client, baseUrl ->
            client.rpc("$baseUrl/api/rpc/authed").withService<PlaybackService>()
        }

    override suspend fun playbackService(): PlaybackService = cache.get()

    override suspend fun invalidate() = cache.invalidate()
}
