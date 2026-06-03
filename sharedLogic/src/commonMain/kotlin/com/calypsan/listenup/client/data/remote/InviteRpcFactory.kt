package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.InviteServicePublic
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
 * Supplies the [InviteServicePublic] kotlinx.rpc proxy backing the anonymous
 * invite surface — landing-page lookup ([InviteServicePublic.lookupInvite]) and
 * claim ([InviteServicePublic.claimInvite]).
 *
 * An interface so the repository depends on a seam that can be faked in tests —
 * [KtorInviteRpcFactory] is the production implementation over WebSocket RPC.
 * Mirrors [TagRpcFactory] — the established RPC-factory-seam precedent. Public
 * only: the client never talks to the authed invite-management service (admin
 * invite mgmt is deferred), so there is no authed proxy here.
 */
interface InviteRpcFactory {
    /** Returns the cached [InviteServicePublic] proxy, connecting on first use. */
    suspend fun publicService(): InviteServicePublic

    /** Drop the cached proxy and the RPC-flavored HttpClient. */
    suspend fun invalidate()
}

/**
 * Mounts the [InviteServicePublic] proxy over `/api/rpc/public` — the anonymous
 * RPC surface (no bearer token).
 *
 * Mirrors [KtorTagRpcFactory]: `rpc(url)` returns a cold
 * [kotlinx.rpc.krpc.ktor.client.KtorRpcClient] that opens its WebSocket on the
 * first message, so the proxy is cached per mount and reused. [invalidate] drops
 * the cached proxy and the RPC-flavored [HttpClient] whenever the underlying
 * client is recycled (server URL changed, manual reset).
 *
 * Wire serialization uses the contract-layer [contractJson] — one wire format,
 * two transports.
 */
open class KtorInviteRpcFactory(
    private val apiClientFactory: ApiClientFactory,
    private val serverConfig: ServerConfig,
) : InviteRpcFactory,
    RemoteCache {
    private val mutex = Mutex()
    private var cachedRpcClient: HttpClient? = null
    private var cachedPublic: InviteServicePublic? = null

    override suspend fun publicService(): InviteServicePublic =
        mutex.withLock {
            cachedPublic ?: connectPublic().also { cachedPublic = it }
        }

    override suspend fun invalidate() {
        mutex.withLock {
            cachedPublic = null
            cachedRpcClient = null
        }
    }

    internal open suspend fun connectPublic(): InviteServicePublic {
        val baseUrl = rpcBaseUrl()
        return rpcClient().rpc("$baseUrl/api/rpc/public").withService<InviteServicePublic>()
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
