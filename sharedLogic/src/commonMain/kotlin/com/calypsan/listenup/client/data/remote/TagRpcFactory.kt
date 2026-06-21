package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.TagService
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
 * Mounts the [TagService] proxy over `/api/rpc/authed` — the bearer-gated RPC surface.
 *
 * Mirrors [KtorLibraryAdminRpcFactory]: `rpc(url)` returns a cold
 * [kotlinx.rpc.krpc.ktor.client.KtorRpcClient] that opens its WebSocket on the first
 * message, so the proxy is cached per mount and reused. [invalidate] drops the cached
 * proxy and the RPC-flavored [HttpClient] whenever the underlying client is recycled
 * (server URL changed, manual reset).
 *
 * Wire serialization uses the contract-layer [contractJson] — one wire format, two transports.
 *
 * Token rotation is a known phase-1-auth deferral — same across every RPC factory.
 */
internal open class KtorTagRpcFactory(
    private val apiClientFactory: ApiClientFactory,
    private val serverConfig: ServerConfig,
) : TagRpcFactory,
    RemoteCache {
    private val mutex = Mutex()
    private var cachedRpcClient: HttpClient? = null
    private var cachedService: TagService? = null

    override suspend fun get(): TagService =
        mutex.withLock {
            cachedService ?: connect().also { cachedService = it }
        }

    override suspend fun invalidate() {
        mutex.withLock {
            cachedService = null
            cachedRpcClient = null
        }
    }

    internal open suspend fun connect(): TagService {
        val baseUrl = rpcBaseUrl()
        return rpcClient().rpc("$baseUrl/api/rpc/authed").withService<TagService>()
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
