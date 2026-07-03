package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.AuthServiceAuthed
import com.calypsan.listenup.api.AuthServicePublic
import com.calypsan.listenup.client.domain.repository.ServerConfig
import kotlinx.rpc.krpc.ktor.client.rpc
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
 * Production [AuthRpcFactory]: composes two [RpcProxyCache] instances, one per
 * mount — [AuthServicePublic] over `/api/rpc/public`, [AuthServiceAuthed] over
 * `/api/rpc/authed` — since each mount is an independent proxy with its own
 * derived HttpClient. [invalidate] drops both.
 */
internal class KtorAuthRpcFactory(
    apiClientFactory: ApiClientFactory,
    serverConfig: ServerConfig,
) : AuthRpcFactory,
    RemoteCache {
    private val publicCache =
        RpcProxyCache(apiClientFactory, serverConfig) { client, baseUrl ->
            client.rpc("$baseUrl/api/rpc/public").withService<AuthServicePublic>()
        }
    private val authedCache =
        RpcProxyCache(apiClientFactory, serverConfig) { client, baseUrl ->
            client.rpc("$baseUrl/api/rpc/authed").withService<AuthServiceAuthed>()
        }

    override suspend fun publicService(): AuthServicePublic = publicCache.get()

    override suspend fun authedService(): AuthServiceAuthed = authedCache.get()

    override suspend fun invalidate() {
        publicCache.invalidate()
        authedCache.invalidate()
    }
}
