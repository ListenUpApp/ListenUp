package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.BookService
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
 * Supplies the [BookService] kotlinx.rpc proxy that backs on-demand book
 * fetches and server-side FTS search.
 *
 * An interface so repositories depend on a seam that fakes/mocks in tests —
 * [KtorBookRpcFactory] is the production implementation over WebSocket RPC.
 *
 * **Wiring status:** the client proxy is fully formed, but the `:server`
 * module does not yet `registerService<BookService>` on its RPC surface, so
 * end-to-end calls will not function until that separately-tracked task lands.
 */
interface BookRpcFactory {
    /** Returns the cached [BookService] proxy, connecting on first use. */
    suspend fun bookService(): BookService

    /** Drop the cached proxy and the RPC-flavored HttpClient. */
    suspend fun invalidate()
}

/**
 * Mounts the [BookService] proxy over `/api/rpc/authed` — the bearer-gated RPC
 * surface.
 *
 * Mirrors [AuthRpcFactory]: `rpc(url)` returns a cold
 * [kotlinx.rpc.krpc.ktor.client.KtorRpcClient] that opens its WebSocket on the
 * first message, so the proxy is cached per mount and reused. [invalidate]
 * drops the cached proxy and the RPC-flavored [HttpClient] whenever the
 * underlying client is recycled (server URL changed, manual reset).
 *
 * Wire serialization is the contract-layer [contractJson] — one wire format,
 * two transports.
 */
class KtorBookRpcFactory(
    private val apiClientFactory: ApiClientFactory,
    private val serverConfig: ServerConfig,
) : BookRpcFactory {
    private val mutex = Mutex()
    private var cachedRpcClient: HttpClient? = null
    private var cachedService: BookService? = null

    override suspend fun bookService(): BookService =
        mutex.withLock {
            cachedService ?: connect().also { cachedService = it }
        }

    override suspend fun invalidate() {
        mutex.withLock {
            cachedService = null
            cachedRpcClient = null
        }
    }

    private suspend fun connect(): BookService {
        val baseUrl = rpcBaseUrl()
        return rpcClient().rpc("$baseUrl/api/rpc/authed").withService<BookService>()
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
