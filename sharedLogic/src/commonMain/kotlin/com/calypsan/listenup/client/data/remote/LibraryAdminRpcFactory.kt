package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.LibraryAdminService
import com.calypsan.listenup.client.domain.repository.ServerConfig
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.withService

/**
 * Supplies the [LibraryAdminService] kotlinx.rpc proxy that backs library and folder
 * lifecycle administration — create, rename, delete, add/remove folders, trigger scans,
 * and onboarding helpers ([LibraryAdminService.getSetupStatus], [LibraryAdminService.browseFilesystem]).
 *
 * An interface so repositories and ViewModels depend on a seam that fakes/mocks in tests —
 * [KtorLibraryAdminRpcFactory] is the production implementation over WebSocket RPC.
 *
 * Follows the established RPC factory-seam precedent in this codebase.
 */
interface LibraryAdminRpcFactory {
    /** Returns the cached [LibraryAdminService] proxy, connecting on first use. */
    suspend fun get(): LibraryAdminService

    /** Drop the cached proxy and the RPC-flavored HttpClient. */
    suspend fun invalidate()
}

/**
 * Production [LibraryAdminRpcFactory]: delegates the connection lifecycle to
 * [RpcProxyCache], supplying the `/api/rpc/authed` mount and the reified
 * [LibraryAdminService] binding.
 */
internal class KtorLibraryAdminRpcFactory(
    apiClientFactory: ApiClientFactory,
    serverConfig: ServerConfig,
) : LibraryAdminRpcFactory,
    RemoteCache {
    private val cache =
        RpcProxyCache(apiClientFactory, serverConfig) { client, baseUrl ->
            client.rpc("$baseUrl/api/rpc/authed").withService<LibraryAdminService>()
        }

    override suspend fun get(): LibraryAdminService = cache.get()

    override suspend fun invalidate() = cache.invalidate()
}
