package com.calypsan.listenup.client.di

import com.calypsan.listenup.api.PushService
import com.calypsan.listenup.api.push.PushPlatform
import com.calypsan.listenup.client.data.push.PushRegistrar
import com.calypsan.listenup.client.data.push.PushTokenProvider
import com.calypsan.listenup.client.data.remote.rpcChannel
import com.calypsan.listenup.client.data.repository.PushRepositoryImpl
import com.calypsan.listenup.client.domain.repository.PushRepository
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Push aggregate Koin wiring — RPC channel, repository, and registrar for device
 * push-token registration.
 *
 * External dependencies (owned by other modules):
 *  - [com.calypsan.listenup.client.data.remote.ApiClientFactory] — `networkModule`
 *  - [com.calypsan.listenup.client.domain.repository.ServerConfig] — `settingsModule`
 *  - [com.calypsan.listenup.client.data.remote.RpcAuthRecovery] — `networkModule`
 *  - [com.calypsan.listenup.client.domain.repository.InstanceRepository] — `settingsModule`
 *  - [PushPlatform] — bound `ANDROID` in the Android platform module (`androidModule` in
 *    `ListenUp.kt`) and `IOS` in the iOS one. Resolved with `getOrNull()`: web and desktop have
 *    no value to bind, and a hard `get()` made this whole module unconstructable there.
 *  - [PushTokenProvider] — bound only where a real platform hook exists (the
 *    Android platform module binds `FcmTokenProvider`); resolved here via
 *    `getOrNull()` so its absence (desktop, or an Android build without Play
 *    services) is a normal, non-crashing case.
 */
internal val pushClientModule: Module =
    module {
        // PushService RPC channel — kotlinx.rpc dispatch for token registration and
        // diagnostics (authed mount only; no local mirror). Authed (self-healing) by default.
        rpcChannel<PushService>()

        // PushRepository — device push-token registration (SOLID: interface in domain, impl in data)
        single<PushRepository> {
            PushRepositoryImpl(
                channel = rpcChannel(),
                // Owned by clientAuthModule — the same public channel the pre-auth status
                // streams ride; watch registration is a pre-auth call by definition (#1068).
                publicAuthChannel = rpcChannel(),
            )
        }

        // PushRegistrar — orchestrates registration post-auth, on rotation, and on toggle
        // change. `tokenProvider` is nullable by design: no binding means no platform push
        // hook on this build.
        single {
            PushRegistrar(
                instanceRepository = get(),
                pushRepository = get(),
                tokenProvider = getOrNull(),
                // Optional for the same reason the provider is — and absent in the same builds.
                // A hard `get()` here made the whole push graph unconstructable on web and
                // desktop, where the enum has no value to bind.
                platform = getOrNull(),
            )
        }
    }
