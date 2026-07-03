package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.client.domain.repository.ServerConfig
import io.ktor.client.HttpClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.serialization.json.json

/**
 * The shared stateful body of every post-login RPC factory: a Mutex-guarded,
 * invalidate-able cache of one kotlinx.rpc service proxy plus the RPC-flavored
 * [HttpClient] it rides on.
 *
 * `rpc(url)` returns a cold [kotlinx.rpc.krpc.ktor.client.KtorRpcClient] that opens
 * its WebSocket on the first message, so the proxy is cached and reused. [invalidate]
 * drops both the cached proxy and the derived [HttpClient] — they are principal-bound
 * and must not survive a logout, re-login, or server-URL change ([RpcCacheInvalidator]
 * sweeps every [RemoteCache] for exactly that reason).
 *
 * [connect] is where reification lives: `withService<T>()` needs a reified type
 * parameter, so each factory supplies a lambda like
 * `{ client, baseUrl -> client.rpc("$baseUrl/api/rpc/authed").withService<FooService>() }`.
 *
 * Wire serialization is the contract-layer [contractJson] — one wire format, two
 * transports.
 */
internal class RpcProxyCache<T : Any>(
    private val apiClientFactory: ApiClientFactory,
    private val serverConfig: ServerConfig,
    private val connect: suspend (rpcClient: HttpClient, wsBaseUrl: String) -> T,
) : RemoteCache {
    private val mutex = Mutex()
    private var cachedRpcClient: HttpClient? = null
    private var cachedProxy: T? = null

    /** Returns the cached proxy, connecting on first use. */
    suspend fun get(): T =
        mutex.withLock {
            cachedProxy ?: run {
                // Resolve the URL BEFORE deriving the client: a missing server URL
                // must throw ServerUrlNotConfiguredException without caching anything.
                val wsBaseUrl = rpcBaseUrl()
                connect(rpcClient(), wsBaseUrl).also { cachedProxy = it }
            }
        }

    override suspend fun invalidate() {
        mutex.withLock {
            cachedProxy = null
            cachedRpcClient = null
        }
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

/**
 * Translate an HTTP-scheme URL into its WebSocket equivalent. kotlinx.rpc
 * 0.10.x's `client.rpc(url)` opens a WebSocket session and does NOT
 * auto-upgrade `http://` → `ws://`; passing the raw HTTP URL produces a
 * plain GET that the server rejects with 400. The translation lives in the
 * RPC layer (not on `ServerConfig`) because the WS scheme is an RPC-transport
 * concern — REST callers want the unmodified URL.
 *
 * Visibility is `internal` so unit tests can pin every branch (this is the
 * regression net for the F12-discovered production bug).
 */
internal fun toWebSocketScheme(httpUrl: String): String =
    when {
        httpUrl.startsWith("https://") -> "wss://" + httpUrl.removePrefix("https://")
        httpUrl.startsWith("http://") -> "ws://" + httpUrl.removePrefix("http://")
        httpUrl.startsWith("ws://") || httpUrl.startsWith("wss://") -> httpUrl
        else -> error("Server URL has unsupported scheme: $httpUrl")
    }
