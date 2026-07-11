package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.campfire.CampfireRpcTransport
import com.calypsan.listenup.client.campfire.CampfireSessionController
import com.calypsan.listenup.client.campfire.CampfireTransport
import com.calypsan.listenup.client.data.remote.CampfireRpcFactory
import com.calypsan.listenup.client.data.remote.KtorCampfireRpcFactory
import com.calypsan.listenup.client.data.remote.RemoteCache
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.binds
import org.koin.dsl.module

/** Koin qualifier for the application-lifetime `CoroutineScope` — see `appCoreModule`. */
private const val APP_SCOPE = "appScope"

/**
 * Campfire (co-listening) aggregate Koin wiring — RPC proxy, transport seam, and the
 * per-session controller (Task 8).
 *
 * External dependencies (owned by other modules):
 *  - [com.calypsan.listenup.client.data.remote.ApiClientFactory] — `networkModule`
 *  - [com.calypsan.listenup.client.domain.repository.ServerConfig] — `settingsModule`
 *  - [com.calypsan.listenup.client.data.remote.RpcAuthRecovery] — `networkModule`
 *  - [com.calypsan.listenup.client.playback.PlaybackManager] / [com.calypsan.listenup.client.playback.PlaybackController] —
 *    owned by the platform playback module
 *  - [com.calypsan.listenup.client.domain.repository.UserRepository] — `socialModule`
 *  - `CoroutineScope` named `appScope` — `appCoreModule`
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

        // CampfireSessionController — one per joined session (NOT a singleton). The UI/ViewModel
        // creates one via get() on join and retains it for the session's duration, calling
        // leave() when done; there is no instance to release back to Koin.
        factory {
            CampfireSessionController(
                transport = get(),
                playbackManager = get(),
                playbackController = get(),
                userRepository = get(),
                scope = get(qualifier = named(APP_SCOPE)),
            )
        }
    }
