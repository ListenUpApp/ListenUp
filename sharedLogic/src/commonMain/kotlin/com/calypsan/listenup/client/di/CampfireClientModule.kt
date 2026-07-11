package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.campfire.CampfireRpcTransport
import com.calypsan.listenup.client.campfire.CampfireTransport
import com.calypsan.listenup.client.data.remote.CampfireRpcFactory
import com.calypsan.listenup.client.data.remote.KtorCampfireRpcFactory
import com.calypsan.listenup.client.data.remote.RemoteCache
import org.koin.core.module.Module
import org.koin.dsl.binds
import org.koin.dsl.module

/**
 * Campfire (co-listening) aggregate Koin wiring — RPC proxy and transport seam for
 * `CampfireSessionController` (Task 8).
 *
 * External dependencies (owned by other modules):
 *  - [com.calypsan.listenup.client.data.remote.ApiClientFactory] — `networkModule`
 *  - [com.calypsan.listenup.client.domain.repository.ServerConfig] — `settingsModule`
 *  - [com.calypsan.listenup.client.data.remote.RpcAuthRecovery] — `networkModule`
 */
internal val campfireClientModule: Module =
    module {
        // CampfireRpcFactory — kotlinx.rpc proxy for CampfireService (authed mount only).
        single<CampfireRpcFactory> {
            KtorCampfireRpcFactory(
                apiClientFactory = get(),
                serverConfig = get(),
                authRecovery = get(),
            )
        } binds arrayOf(RemoteCache::class)

        // CampfireTransport — the session controller's swappable transport seam.
        single<CampfireTransport> {
            CampfireRpcTransport(rpcFactory = get())
        }
    }
