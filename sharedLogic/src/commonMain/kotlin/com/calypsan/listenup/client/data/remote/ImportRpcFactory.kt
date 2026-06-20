package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.ImportService
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
 * Supplies the [ImportService] kotlinx.rpc proxy that backs admin Audiobookshelf
 * import operations.
 *
 * An interface so repositories depend on a seam that can be faked in tests —
 * [KtorImportRpcFactory] is the production implementation over WebSocket RPC.
 * Mirrors [BackupRpcFactory] — the established admin RPC factory precedent.
 */
internal interface ImportRpcFactory {
    /** Returns the cached [ImportService] proxy, connecting on first use. */
    suspend fun get(): ImportService

    /** Drop the cached proxy and the RPC-flavored HttpClient. */
    suspend fun invalidate()
}

/**
 * Mounts the [ImportService] proxy over `/api/rpc/authed` — the bearer-gated RPC surface.
 *
 * Mirrors [KtorBackupRpcFactory]: `rpc(url)` returns a cold
 * [kotlinx.rpc.krpc.ktor.client.KtorRpcClient] that opens its WebSocket on the first
 * message, so the proxy is cached per mount and reused. [invalidate] drops the cached
 * proxy and the RPC-flavored [HttpClient] whenever the underlying client is recycled
 * (server URL changed, manual reset).
 *
 * Wire serialization uses the contract-layer [contractJson] — one wire format, two transports.
 * Token rotation is a known phase-1-auth deferral — shared across every RPC factory.
 */
internal open class KtorImportRpcFactory(
    private val apiClientFactory: ApiClientFactory,
    private val serverConfig: ServerConfig,
) : ImportRpcFactory,
    RemoteCache {
    private val mutex = Mutex()
    private var cachedRpcClient: HttpClient? = null
    private var cachedService: ImportService? = null

    override suspend fun get(): ImportService =
        mutex.withLock {
            cachedService ?: connect().also { cachedService = it }
        }

    override suspend fun invalidate() {
        mutex.withLock {
            cachedService = null
            cachedRpcClient = null
        }
    }

    internal open suspend fun connect(): ImportService {
        val baseUrl = rpcBaseUrl()
        return rpcClient().rpc("$baseUrl/api/rpc/authed").withService<ImportService>()
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
