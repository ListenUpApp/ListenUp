package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.BackupService
import com.calypsan.listenup.client.domain.repository.ServerConfig
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.withService

/**
 * Supplies the [BackupService] kotlinx.rpc proxy that backs admin backup/restore
 * operations.
 *
 * An interface so repositories depend on a seam that can be faked in tests —
 * [KtorBackupRpcFactory] is the production implementation over WebSocket RPC.
 * Mirrors the established RPC factory precedent.
 */
internal interface BackupRpcFactory {
    /** Returns the cached [BackupService] proxy, connecting on first use. */
    suspend fun get(): BackupService

    /** Drop the cached proxy and the RPC-flavored HttpClient. */
    suspend fun invalidate()
}

/**
 * Production [BackupRpcFactory]: delegates the connection lifecycle to
 * [RpcProxyCache], supplying the `/api/rpc/authed` mount and the reified
 * [BackupService] binding.
 */
internal class KtorBackupRpcFactory(
    apiClientFactory: ApiClientFactory,
    serverConfig: ServerConfig,
) : BackupRpcFactory,
    RemoteCache {
    private val cache =
        RpcProxyCache(apiClientFactory, serverConfig) { client, baseUrl ->
            client.rpc("$baseUrl/api/rpc/authed").withService<BackupService>()
        }

    override suspend fun get(): BackupService = cache.get()

    override suspend fun invalidate() = cache.invalidate()
}
