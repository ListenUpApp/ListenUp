package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.AdminUserService
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
 * Supplies the [AdminUserService] kotlinx.rpc proxy backing the admin user-management
 * surface (roster, approval queue, role/permission edits, soft-delete). An interface so
 * repositories depend on a seam that fakes in tests; [KtorAdminUserRpcFactory] is the
 * production implementation over the bearer-gated `/api/rpc/authed` WebSocket.
 *
 * Mirrors [LibraryAdminRpcFactory] — the established RPC-factory shape.
 */
interface AdminUserRpcFactory {
    /** Returns the cached [AdminUserService] proxy, connecting on first use. */
    suspend fun get(): AdminUserService

    /** Drop the cached proxy and the RPC-flavored HttpClient. */
    suspend fun invalidate()
}

/**
 * Production [AdminUserRpcFactory] over the bearer-gated `/api/rpc/authed` WebSocket.
 *
 * Caches the [AdminUserService] proxy and the RPC-flavored [HttpClient] per mount.
 * [invalidate] drops both caches; the next [get] call reconnects fresh — used by
 * [RpcCacheInvalidator] on logout or server-URL change.
 *
 * Declared `open` so tests can subclass and override [connect] with a fake WebSocket.
 *
 * Mirrors [KtorLibraryAdminRpcFactory] — the established RPC-factory shape.
 */
open class KtorAdminUserRpcFactory(
    private val apiClientFactory: ApiClientFactory,
    private val serverConfig: ServerConfig,
) : AdminUserRpcFactory,
    RemoteCache {
    private val mutex = Mutex()
    private var cachedRpcClient: HttpClient? = null
    private var cachedService: AdminUserService? = null

    override suspend fun get(): AdminUserService =
        mutex.withLock {
            cachedService ?: connect().also { cachedService = it }
        }

    override suspend fun invalidate() {
        mutex.withLock {
            cachedService = null
            cachedRpcClient = null
        }
    }

    internal open suspend fun connect(): AdminUserService {
        val baseUrl = rpcBaseUrl()
        return rpcClient().rpc("$baseUrl/api/rpc/authed").withService<AdminUserService>()
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
