package com.calypsan.listenup.client.di

import com.calypsan.listenup.api.push.PushPlatform
import com.calypsan.listenup.client.data.push.PushRegistrar
import com.calypsan.listenup.client.data.push.PushTokenProvider
import com.calypsan.listenup.client.data.remote.KtorPushRpcFactory
import com.calypsan.listenup.client.data.remote.PushRpcFactory
import com.calypsan.listenup.client.data.remote.RemoteCache
import com.calypsan.listenup.client.data.repository.PushRepositoryImpl
import com.calypsan.listenup.client.domain.repository.PushRepository
import org.koin.core.module.Module
import org.koin.dsl.binds
import org.koin.dsl.module

/**
 * Push aggregate Koin wiring — RPC proxy, repository, and registrar for device
 * push-token registration.
 *
 * External dependencies (owned by other modules):
 *  - [com.calypsan.listenup.client.data.remote.ApiClientFactory] — `networkModule`
 *  - [com.calypsan.listenup.client.domain.repository.ServerConfig] — `settingsModule`
 *  - [com.calypsan.listenup.client.data.remote.RpcAuthRecovery] — `networkModule`
 *  - [com.calypsan.listenup.client.domain.repository.InstanceRepository] — `settingsModule`
 *  - [PushPlatform] — bound `ANDROID` in the Android platform module
 *    (`androidModule` in `ListenUp.kt`); the iOS `IOS` binding lands with the
 *    iOS push work.
 *  - [PushTokenProvider] — bound only where a real platform hook exists (the
 *    Android platform module binds `FcmTokenProvider`); resolved here via
 *    `getOrNull()` so its absence (desktop, or an Android build without Play
 *    services) is a normal, non-crashing case.
 */
internal val pushClientModule: Module =
    module {
        // PushRpcFactory — kotlinx.rpc proxy for PushService (authed mount only; no local mirror).
        single<PushRpcFactory> {
            KtorPushRpcFactory(
                apiClientFactory = get(),
                serverConfig = get(),
                authRecovery = get(),
            )
        } binds arrayOf(RemoteCache::class)

        // PushRepository — device push-token registration (SOLID: interface in domain, impl in data)
        single<PushRepository> {
            PushRepositoryImpl(
                rpcFactory = get(),
                platform = get(),
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
            )
        }
    }
