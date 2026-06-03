package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.ScannerService
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
 * Supplies the [ScannerService] kotlinx.rpc proxy used to observe live scan
 * progress (`observeProgress(): Flow<RpcEvent<ScanEvent>>`).
 *
 * [ScannerService] is mounted on the **public** RPC surface (`/api/rpc/public`),
 * so this factory does not attach a bearer token — it mirrors the public-mount
 * pattern of the auth/instance factories. The proxy is cached and reused; the
 * underlying [HttpClient] comes from [ApiClientFactory] (same one the rest of the
 * app uses), so [invalidate] drops the cached proxy when that client is recycled.
 */
interface ScannerRpcFactory {
    /** Returns the cached [ScannerService] proxy, connecting on first use. */
    suspend fun get(): ScannerService

    /** Drop the cached proxy and RPC-flavored client. */
    suspend fun invalidate()
}

/**
 * Production [ScannerRpcFactory]: mounts [ScannerService] over `/api/rpc/public`.
 * Mirrors [KtorLibraryAdminRpcFactory], substituting the public mount.
 */
open class KtorScannerRpcFactory(
    private val apiClientFactory: ApiClientFactory,
    private val serverConfig: ServerConfig,
) : ScannerRpcFactory {
    private val mutex = Mutex()
    private var cachedRpcClient: HttpClient? = null
    private var cachedService: ScannerService? = null

    override suspend fun get(): ScannerService =
        mutex.withLock {
            cachedService ?: connect().also { cachedService = it }
        }

    override suspend fun invalidate() {
        mutex.withLock {
            cachedService = null
            cachedRpcClient = null
        }
    }

    internal open suspend fun connect(): ScannerService {
        val baseUrl = rpcBaseUrl()
        return rpcClient().rpc("$baseUrl/api/rpc/public").withService<ScannerService>()
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
