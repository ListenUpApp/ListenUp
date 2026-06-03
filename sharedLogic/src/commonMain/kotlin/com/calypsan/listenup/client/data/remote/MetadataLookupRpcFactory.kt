package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.MetadataLookupService
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
 * Supplies the [MetadataLookupService] kotlinx.rpc proxy that backs external
 * metadata lookups — Audible search, book/contributor metadata fetch, and
 * metadata-apply operations.
 *
 * An interface so repositories depend on a seam that fakes/mocks in tests —
 * [KtorMetadataLookupRpcFactory] is the production implementation over WebSocket RPC.
 *
 * Mirrors [ContributorRpcFactory] and [SeriesRpcFactory] from B2a-C.
 */
interface MetadataLookupRpcFactory {
    /** Returns the cached [MetadataLookupService] proxy, connecting on first use. */
    suspend fun metadataLookupService(): MetadataLookupService

    /** Drop the cached proxy and the RPC-flavored HttpClient. */
    suspend fun invalidate()
}

/**
 * Mounts the [MetadataLookupService] proxy over `/api/rpc/authed` — the
 * bearer-gated RPC surface.
 *
 * Mirrors [KtorBookRpcFactory]: `rpc(url)` returns a cold
 * [kotlinx.rpc.krpc.ktor.client.KtorRpcClient] that opens its WebSocket on the
 * first message, so the proxy is cached per mount and reused. [invalidate] drops
 * the cached proxy and the RPC-flavored [HttpClient] whenever the underlying
 * client is recycled (server URL changed, manual reset).
 *
 * Wire serialization is the contract-layer [contractJson] — one wire format, two
 * transports.
 *
 * Token rotation is a known phase-1-auth deferral — same across every RPC factory
 * in the codebase. Not solved here.
 */
open class KtorMetadataLookupRpcFactory(
    private val apiClientFactory: ApiClientFactory,
    private val serverConfig: ServerConfig,
) : MetadataLookupRpcFactory,
    RemoteCache {
    private val mutex = Mutex()
    private var cachedRpcClient: HttpClient? = null
    private var cachedService: MetadataLookupService? = null

    override suspend fun metadataLookupService(): MetadataLookupService =
        mutex.withLock {
            cachedService ?: connect().also { cachedService = it }
        }

    override suspend fun invalidate() {
        mutex.withLock {
            cachedService = null
            cachedRpcClient = null
        }
    }

    internal open suspend fun connect(): MetadataLookupService {
        val baseUrl = rpcBaseUrl()
        return rpcClient().rpc("$baseUrl/api/rpc/authed").withService<MetadataLookupService>()
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
