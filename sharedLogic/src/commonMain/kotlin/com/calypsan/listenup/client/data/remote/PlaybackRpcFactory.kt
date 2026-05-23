package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.PlaybackService
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
 * Supplies the [PlaybackService] kotlinx.rpc proxy that backs playback preparation
 * and position recording (the Playback-P1 client write path).
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
 * Mounts the [PlaybackService] proxy over `/api/rpc/authed` — the bearer-gated RPC
 * surface.
 *
 * Mirrors [KtorBookRpcFactory]: `rpc(url)` returns a cold
 * [kotlinx.rpc.krpc.ktor.client.KtorRpcClient] that opens its WebSocket on the first
 * message, so the proxy is cached per mount and reused. [invalidate] drops the cached
 * proxy and the RPC-flavored [HttpClient] whenever the underlying client is recycled
 * (server URL changed, manual reset).
 *
 * Wire serialization is the contract-layer [contractJson] — one wire format, two transports.
 */
class KtorPlaybackRpcFactory(
    private val apiClientFactory: ApiClientFactory,
    private val serverConfig: ServerConfig,
) : PlaybackRpcFactory {
    private val mutex = Mutex()
    private var cachedRpcClient: HttpClient? = null
    private var cachedService: PlaybackService? = null

    override suspend fun playbackService(): PlaybackService =
        mutex.withLock {
            cachedService ?: connect().also { cachedService = it }
        }

    override suspend fun invalidate() {
        mutex.withLock {
            cachedService = null
            cachedRpcClient = null
        }
    }

    private suspend fun connect(): PlaybackService {
        val baseUrl = rpcBaseUrl()
        return rpcClient().rpc("$baseUrl/api/rpc/authed").withService<PlaybackService>()
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
                ?: error("Server URL not configured — cannot open RPC connection")
        return toWebSocketScheme(httpUrl)
    }
}
