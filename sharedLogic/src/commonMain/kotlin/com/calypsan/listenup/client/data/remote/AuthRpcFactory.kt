package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.AuthServiceAuthed
import com.calypsan.listenup.api.AuthServicePublic
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
 * Mounts the two kotlinx.rpc service proxies the client uses to talk to
 * the auth contract:
 *  - [AuthServicePublic] over `/api/rpc/public` — anonymous; login, register,
 *    refresh, setupRoot.
 *  - [AuthServiceAuthed] over `/api/rpc/authed` — bearer-gated; logout,
 *    currentUser, listSessions.
 *
 * An interface so the repository depends on a seam that can be faked in tests —
 * [KtorAuthRpcFactory] is the production implementation over WebSocket RPC.
 * Mirrors [InviteRpcFactory] — the established RPC-factory-seam precedent.
 */
internal interface AuthRpcFactory {
    /** Returns the cached [AuthServicePublic] proxy, connecting on first use. */
    suspend fun publicService(): AuthServicePublic

    /** Returns the cached [AuthServiceAuthed] proxy, connecting on first use. */
    suspend fun authedService(): AuthServiceAuthed

    /** Drop cached proxies and the RPC-flavored HttpClient. */
    suspend fun invalidate()
}

/**
 * Mounts the two [AuthServicePublic]/[AuthServiceAuthed] proxies over
 * `/api/rpc/public` and `/api/rpc/authed`.
 *
 * Each `rpc(url)` call returns a cold [kotlinx.rpc.krpc.ktor.client.KtorRpcClient]
 * that opens its WebSocket on the first message — so the proxies are cached
 * per mount and reused. [invalidate] drops both the cached proxies *and* the
 * RPC-flavored HttpClient that wraps them; the configured [ApiClientFactory]
 * calls [invalidate] whenever the underlying [HttpClient] is recycled (server
 * URL changed, manual reset).
 *
 * Wire serialization is the contract-layer [contractJson] — same instance
 * the REST surface and contract round-trip tests use. One wire format,
 * two transports.
 */
internal class KtorAuthRpcFactory(
    private val apiClientFactory: ApiClientFactory,
    private val serverConfig: ServerConfig,
) : AuthRpcFactory,
    RemoteCache {
    private val mutex = Mutex()
    private var cachedRpcClient: HttpClient? = null
    private var cachedPublic: AuthServicePublic? = null
    private var cachedAuthed: AuthServiceAuthed? = null

    override suspend fun publicService(): AuthServicePublic =
        mutex.withLock {
            cachedPublic ?: connectPublic().also { cachedPublic = it }
        }

    override suspend fun authedService(): AuthServiceAuthed =
        mutex.withLock {
            cachedAuthed ?: connectAuthed().also { cachedAuthed = it }
        }

    override suspend fun invalidate() {
        mutex.withLock {
            cachedPublic = null
            cachedAuthed = null
            cachedRpcClient = null
        }
    }

    private suspend fun connectPublic(): AuthServicePublic {
        val baseUrl = rpcBaseUrl()
        return rpcClient().rpc("$baseUrl/api/rpc/public").withService<AuthServicePublic>()
    }

    private suspend fun connectAuthed(): AuthServiceAuthed {
        val baseUrl = rpcBaseUrl()
        return rpcClient().rpc("$baseUrl/api/rpc/authed").withService<AuthServiceAuthed>()
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
