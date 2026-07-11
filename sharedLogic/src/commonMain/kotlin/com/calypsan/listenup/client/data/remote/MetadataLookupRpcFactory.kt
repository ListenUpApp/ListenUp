package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.MetadataLookupService
import com.calypsan.listenup.client.domain.repository.ServerConfig
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.withService

/**
 * Supplies the [MetadataLookupService] kotlinx.rpc proxy that backs external
 * metadata lookups — Audible search, book/contributor metadata fetch, and
 * metadata-apply operations.
 *
 * An interface so repositories depend on a seam that fakes/mocks in tests —
 * [KtorMetadataLookupRpcFactory] is the production implementation over WebSocket RPC.
 *
 * Mirrors [BookRpcFactory] from B2a-C.
 */
internal interface MetadataLookupRpcFactory {
    /** Returns the cached [MetadataLookupService] proxy, connecting on first use. */
    suspend fun metadataLookupService(): MetadataLookupService

    /** Drop the cached proxy and the RPC-flavored HttpClient. */
    suspend fun invalidate()
}

/**
 * Production [MetadataLookupRpcFactory]: delegates the connection lifecycle to
 * [RpcProxyCache], supplying the `/api/rpc/authed` mount and the reified
 * [MetadataLookupService] binding.
 */
internal class KtorMetadataLookupRpcFactory(
    apiClientFactory: ApiClientFactory,
    serverConfig: ServerConfig,
) : MetadataLookupRpcFactory,
    RemoteCache {
    private val cache =
        RpcProxyCache(apiClientFactory, serverConfig) { client, baseUrl ->
            client.rpc("$baseUrl/api/rpc/authed").withService<MetadataLookupService>()
        }

    override suspend fun metadataLookupService(): MetadataLookupService = cache.get()

    override suspend fun invalidate() = cache.invalidate()
}
