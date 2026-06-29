package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.LibraryAdminService
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
 * Supplies the [LibraryAdminService] kotlinx.rpc proxy that backs library and folder
 * lifecycle administration — create, rename, delete, add/remove folders, trigger scans,
 * and onboarding helpers ([LibraryAdminService.getSetupStatus], [LibraryAdminService.browseFilesystem]).
 *
 * An interface so repositories and ViewModels depend on a seam that fakes/mocks in tests —
 * [KtorLibraryAdminRpcFactory] is the production implementation over WebSocket RPC.
 *
 * Mirrors [MetadataLookupRpcFactory] from B2b — the established precedent for RPC factory
 * seams in this codebase.
 */
interface LibraryAdminRpcFactory {
    /** Returns the cached [LibraryAdminService] proxy, connecting on first use. */
    suspend fun get(): LibraryAdminService

    /** Drop the cached proxy and the RPC-flavored HttpClient. */
    suspend fun invalidate()
}

/**
 * Mounts the [LibraryAdminService] proxy over `/api/rpc/authed` — the
 * bearer-gated RPC surface.
 *
 * Mirrors [KtorMetadataLookupRpcFactory]: `rpc(url)` returns a cold
 * [kotlinx.rpc.krpc.ktor.client.KtorRpcClient] that opens its WebSocket on the
 * first message, so the proxy is cached per mount and reused. [invalidate] drops
 * the cached proxy and the RPC-flavored [HttpClient] whenever the underlying
 * client is recycled (server URL changed, manual reset).
 *
 * Wire serialization is the contract-layer [contractJson] — one wire format, two transports.
 *
 * Token rotation is not yet implemented — the same gap exists in every RPC factory
 * in the codebase. Not solved here.
 */
internal open class KtorLibraryAdminRpcFactory(
    private val apiClientFactory: ApiClientFactory,
    private val serverConfig: ServerConfig,
) : LibraryAdminRpcFactory,
    RemoteCache {
    private val mutex = Mutex()
    private var cachedRpcClient: HttpClient? = null
    private var cachedService: LibraryAdminService? = null

    override suspend fun get(): LibraryAdminService =
        mutex.withLock {
            cachedService ?: connect().also { cachedService = it }
        }

    override suspend fun invalidate() {
        mutex.withLock {
            cachedService = null
            cachedRpcClient = null
        }
    }

    internal open suspend fun connect(): LibraryAdminService {
        val baseUrl = rpcBaseUrl()
        return rpcClient().rpc("$baseUrl/api/rpc/authed").withService<LibraryAdminService>()
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
