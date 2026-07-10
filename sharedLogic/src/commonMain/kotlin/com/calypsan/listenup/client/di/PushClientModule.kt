package com.calypsan.listenup.client.di

import com.calypsan.listenup.api.push.PushPlatform
import com.calypsan.listenup.client.data.remote.KtorPushRpcFactory
import com.calypsan.listenup.client.data.remote.PushRpcFactory
import com.calypsan.listenup.client.data.remote.RemoteCache
import com.calypsan.listenup.client.data.repository.PushRepositoryImpl
import com.calypsan.listenup.client.domain.repository.PushRepository
import org.koin.core.module.Module
import org.koin.dsl.binds
import org.koin.dsl.module

/**
 * Push aggregate Koin wiring — RPC proxy and repository for device push-token
 * registration.
 *
 * External dependencies (owned by other modules):
 *  - [com.calypsan.listenup.client.data.remote.ApiClientFactory] — `networkModule`
 *  - [com.calypsan.listenup.client.domain.repository.ServerConfig] — `settingsModule`
 *  - [com.calypsan.listenup.client.data.remote.RpcAuthRecovery] — `networkModule`
 *
 * The [PushPlatform] binding below is a **stopgap**: it hardcodes `ANDROID` here
 * because this task (client RPC plumbing, C2) only touches commonMain. The
 * per-platform value belongs in the platform entry point once it exists — C3/C4
 * move this binding to `androidMain` (and add the iOS `IOS` equivalent) as part
 * of wiring up the real FCM token provider.
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

        // TODO(C3/C4): relocate to a platform module once FcmTokenProvider exists; bind IOS on Apple targets.
        single<PushPlatform> { PushPlatform.ANDROID }

        // PushRepository — device push-token registration (SOLID: interface in domain, impl in data)
        single<PushRepository> {
            PushRepositoryImpl(
                rpcFactory = get(),
                platform = get(),
            )
        }
    }
