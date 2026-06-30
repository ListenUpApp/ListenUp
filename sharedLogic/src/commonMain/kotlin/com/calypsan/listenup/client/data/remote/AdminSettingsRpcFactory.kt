package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.AdminSettingsService
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
 * Supplies the [AdminSettingsService] kotlinx.rpc proxy backing the admin server-identity
 * settings surface (server name + remote URL). An interface so repositories depend
 * on a seam that fakes in tests; [KtorAdminSettingsRpcFactory] is the production implementation
 * over the bearer-gated `/api/rpc/authed` WebSocket.
 *
 * Mirrors [AdminUserRpcFactory] — the established RPC-factory shape.
 */
internal interface AdminSettingsRpcFactory {
    /** Returns the cached [AdminSettingsService] proxy, connecting on first use. */
    suspend fun get(): AdminSettingsService

    /** Drop the cached proxy and the RPC-flavored HttpClient. */
    suspend fun invalidate()
}

/**
 * Production [AdminSettingsRpcFactory] over the bearer-gated `/api/rpc/authed` WebSocket.
 *
 * Caches the [AdminSettingsService] proxy and the RPC-flavored [HttpClient] per mount.
 * [invalidate] drops both caches; the next [get] call reconnects fresh — used by
 * [RpcCacheInvalidator] on logout or server-URL change.
 *
 * Declared `open` so tests can subclass and override [connect] with a fake WebSocket.
 *
 * Mirrors [KtorAdminUserRpcFactory] — the established RPC-factory shape.
 */
internal open class KtorAdminSettingsRpcFactory(
    private val apiClientFactory: ApiClientFactory,
    private val serverConfig: ServerConfig,
) : AdminSettingsRpcFactory,
    RemoteCache {
    private val mutex = Mutex()
    private var cachedRpcClient: HttpClient? = null
    private var cachedService: AdminSettingsService? = null

    override suspend fun get(): AdminSettingsService =
        mutex.withLock {
            cachedService ?: connect().also { cachedService = it }
        }

    override suspend fun invalidate() {
        mutex.withLock {
            cachedService = null
            cachedRpcClient = null
        }
    }

    internal open suspend fun connect(): AdminSettingsService {
        val baseUrl = rpcBaseUrl()
        return rpcClient().rpc("$baseUrl/api/rpc/authed").withService<AdminSettingsService>()
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
