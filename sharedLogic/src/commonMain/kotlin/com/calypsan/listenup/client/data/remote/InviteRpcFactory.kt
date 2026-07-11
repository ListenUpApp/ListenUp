package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.InviteService
import com.calypsan.listenup.api.InviteServicePublic
import com.calypsan.listenup.client.domain.repository.ServerConfig
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.withService

/**
 * Supplies the [InviteServicePublic] kotlinx.rpc proxy backing the anonymous
 * invite surface — landing-page lookup ([InviteServicePublic.lookupInvite]) and
 * claim ([InviteServicePublic.claimInvite]) — and the authed [InviteService]
 * proxy for admin invite management.
 *
 * An interface so the repository depends on a seam that can be faked in tests —
 * [KtorInviteRpcFactory] is the production implementation over WebSocket RPC.
 * Mirrors the established RPC-factory-seam precedent.
 */
internal interface InviteRpcFactory {
    /** Returns the cached [InviteServicePublic] proxy, connecting on first use. */
    suspend fun publicService(): InviteServicePublic

    /** Returns the cached authed [InviteService] proxy, connecting on first use. */
    suspend fun adminService(): InviteService

    /** Drop the cached proxies and the RPC-flavored HttpClient. */
    suspend fun invalidate()
}

/**
 * Production [InviteRpcFactory]: composes two [RpcProxyCache] instances, one per
 * mount — [InviteServicePublic] over `/api/rpc/public`, the admin [InviteService]
 * over `/api/rpc/authed` — since each mount is an independent proxy with its own
 * derived HttpClient. [invalidate] drops both.
 */
internal class KtorInviteRpcFactory(
    apiClientFactory: ApiClientFactory,
    serverConfig: ServerConfig,
) : InviteRpcFactory,
    RemoteCache {
    private val publicCache =
        RpcProxyCache(apiClientFactory, serverConfig) { client, baseUrl ->
            client.rpc("$baseUrl/api/rpc/public").withService<InviteServicePublic>()
        }
    private val adminCache =
        RpcProxyCache(apiClientFactory, serverConfig) { client, baseUrl ->
            client.rpc("$baseUrl/api/rpc/authed").withService<InviteService>()
        }

    override suspend fun publicService(): InviteServicePublic = publicCache.get()

    override suspend fun adminService(): InviteService = adminCache.get()

    override suspend fun invalidate() {
        publicCache.invalidate()
        adminCache.invalidate()
    }
}
