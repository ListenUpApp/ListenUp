package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.TagService
import com.calypsan.listenup.client.domain.repository.ServerConfig
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.withService

/**
 * Supplies the [TagService] kotlinx.rpc proxy that backs tag lifecycle and observation
 * operations — [TagService.listTags], [TagService.addTagToBook], [TagService.deleteTag], etc.
 *
 * An interface so repositories and ViewModels depend on a seam that can be faked in tests —
 * [KtorTagRpcFactory] is the production implementation over WebSocket RPC.
 *
 * Mirrors [LibraryAdminRpcFactory] — the established precedent for RPC factory seams.
 */
internal interface TagRpcFactory {
    /** Returns the cached [TagService] proxy, connecting on first use. */
    suspend fun get(): TagService

    /** Drop the cached proxy and the RPC-flavored HttpClient. */
    suspend fun invalidate()
}

/**
 * Production [TagRpcFactory]: delegates the connection lifecycle to
 * [RpcProxyCache], supplying the `/api/rpc/authed` mount and the reified
 * [TagService] binding.
 */
internal class KtorTagRpcFactory(
    apiClientFactory: ApiClientFactory,
    serverConfig: ServerConfig,
) : TagRpcFactory,
    RemoteCache {
    private val cache =
        RpcProxyCache(apiClientFactory, serverConfig) { client, baseUrl ->
            client.rpc("$baseUrl/api/rpc/authed").withService<TagService>()
        }

    override suspend fun get(): TagService = cache.get()

    override suspend fun invalidate() = cache.invalidate()
}
