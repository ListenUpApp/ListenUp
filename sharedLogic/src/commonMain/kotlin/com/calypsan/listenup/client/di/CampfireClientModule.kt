package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.campfire.ActiveCampfireCoordinator
import com.calypsan.listenup.client.campfire.CampfireDiscoveryRepository
import com.calypsan.listenup.client.campfire.CampfireRpcTransport
import com.calypsan.listenup.client.campfire.CampfireSessionController
import com.calypsan.listenup.client.campfire.CampfireTransport
import com.calypsan.listenup.api.CampfireService
import com.calypsan.listenup.client.data.remote.rpcChannel
import com.calypsan.listenup.client.data.sync.CampfireRefreshSignal
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.presentation.campfire.CampfireBookPickerViewModel
import com.calypsan.listenup.client.presentation.campfire.CampfireViewModel
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

/** Koin qualifier for the application-lifetime `CoroutineScope` — see `appCoreModule`. */
private const val APP_SCOPE = "appScope"

/**
 * Campfire (co-listening) aggregate Koin wiring — RPC proxy, transport seam, the per-session
 * controller (Task 8), the discovery repository, and the session ViewModel (Task 9).
 *
 * External dependencies (owned by other modules):
 *  - [com.calypsan.listenup.client.data.remote.ApiClientFactory] — `networkModule`
 *  - [com.calypsan.listenup.client.domain.repository.ServerConfig] — `settingsModule`
 *  - [com.calypsan.listenup.client.data.remote.RpcAuthRecovery] — `networkModule`
 *  - [com.calypsan.listenup.client.playback.PlaybackManager] / [com.calypsan.listenup.client.playback.PlaybackController] —
 *    owned by the platform playback module
 *  - [com.calypsan.listenup.client.domain.repository.UserRepository] — `socialModule`
 *  - [CampfireRefreshSignal] — `clientSyncModule` (mirrors `PresenceRefreshSignal`'s home)
 *  - [com.calypsan.listenup.core.error.ErrorBus] — `appCoreModule`
 *  - `CoroutineScope` named `appScope` — `appCoreModule`
 *  - [BookRepository] — `bookModule` (the Discover "Start a campfire" book-picker's data source)
 */
internal val campfireClientModule: Module =
    module {
        // CampfireService RPC channel — kotlinx.rpc dispatch for co-listening (authed mount only).
        rpcChannel<CampfireService>()

        // CampfireTransport — the session controller's swappable transport seam.
        single<CampfireTransport> {
            CampfireRpcTransport(channel = rpcChannel())
        }

        // CampfireDiscoveryRepository — in-memory (no Room) discovery mirror for the book-detail
        // live badge and the Discover "Live now" row, re-fetched on every CampfireRefreshSignal
        // ping (the server's CampfiresChanged nudge) and on each screen's subscribe.
        single {
            CampfireDiscoveryRepository(
                transport = get(),
                refreshSignal = get(),
            )
        }

        // CampfireSessionController — process-`single` (F2). Only one campfire is live at a time and
        // its jobs already run on appScope, so a single instance IS the session: any ViewModel
        // generation (e.g. after an activity rebuild on task-swipe) re-attaches to the same live
        // controller rather than spawning a fresh, empty one that orphans the running session.
        single {
            CampfireSessionController(
                transport = get(),
                playbackManager = get(),
                playbackController = get(),
                userRepository = get(),
                scope = get(qualifier = named(APP_SCOPE)),
            )
        }

        // ActiveCampfireCoordinator — process-scope liveness seam read by NowPlayingViewModel (B3).
        // Owns the always-on mirror of the single controller's state on appScope (F2).
        single {
            ActiveCampfireCoordinator(
                controller = get(),
                scope = get(qualifier = named(APP_SCOPE)),
            )
        }

        // CampfireViewModel — one per screen instance (factory). Wraps the single controller; the
        // liveness mirror lives in the coordinator now, so the VM no longer holds it (F2).
        factory {
            CampfireViewModel(
                controller = get(),
                transport = get(),
                errorBus = get(),
                userRepository = get(),
            )
        }

        // CampfireBookPickerViewModel — one per Discover "Start a campfire" sheet instance.
        factory {
            CampfireBookPickerViewModel(bookRepository = get())
        }
    }
