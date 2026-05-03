package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.data.remote.AuthRpcFactory
import com.calypsan.listenup.client.data.repository.AuthRepositoryImpl
import com.calypsan.listenup.client.data.repository.AuthSessionStore
import com.calypsan.listenup.client.data.repository.RegistrationStatusStreamImpl
import com.calypsan.listenup.client.domain.repository.AuthRepository
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.RegistrationStatusStream
import com.calypsan.listenup.client.domain.usecase.auth.LoginUseCase
import com.calypsan.listenup.client.domain.usecase.auth.LogoutUseCase
import com.calypsan.listenup.client.domain.usecase.auth.RegisterUseCase
import com.calypsan.listenup.client.domain.usecase.auth.SetupUseCase
import com.calypsan.listenup.client.playback.PlaybackManager
import org.koin.core.module.Module
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * Auth domain wiring — every binding the auth flow owns. Pulled out of
 * the monolithic `Koin.kt` so the auth surface is auditable in one
 * place and so `module.verify()` can run a leaf check against it.
 *
 * What lives here:
 *  - [AuthSession] / [AuthSessionStore] — token storage + auth-state flow.
 *  - [AuthRpcFactory] — kotlinx.rpc proxies for the split AuthService contracts.
 *  - [AuthRepository] / [AuthRepositoryImpl] — the typed `AppResult` surface.
 *  - [RegistrationStatusStream] — SSE for "is my registration approved yet?".
 *  - The four auth use cases.
 *
 * What deliberately stays in `Koin.kt`:
 *  - `ApiClientFactory` — bearer-equipped HttpClient consumed by every API,
 *    not just auth. Its refresh-callback `{ get<AuthRepository>() … }` resolves
 *    across module boundaries at runtime, which is standard Koin.
 *  - `InviteApi` / `InviteRepository` — invite is its own domain that happens
 *    to call `AuthSession.saveAuthTokens` on a successful claim. Tracked for
 *    its own migration phase.
 */
val clientAuthModule: Module =
    module {
        // Token storage + AuthState derivation. AuthSessionStore depends on
        // ServerConfig (to read the URL during state derivation); the settings
        // impl in `dataModule` depends back on AuthSession (set-URL/disconnect
        // updates auth state). The cycle is real and resolved by Koin's lazy
        // single mechanism — see the comments at the SettingsRepositoryImpl
        // binding in Koin.kt.
        singleOf(::AuthSessionStore) bind AuthSession::class

        // kotlinx.rpc proxies for AuthServicePublic + AuthServiceAuthed.
        // Cached per mount; invalidated alongside ApiClientFactory whenever
        // the underlying HttpClient is recycled (server URL change). The
        // invalidation handshake lives at the ServerRepository binding in
        // Koin.kt — when you add another remote cache, drop an
        // `invalidate()` call there too.
        singleOf(::AuthRpcFactory)

        // Thin RPC adapter — translates contract calls into typed AppResult.
        singleOf(::AuthRepositoryImpl) bind AuthRepository::class

        // SSE stream for the pending-approval flow.
        singleOf(::RegistrationStatusStreamImpl) bind RegistrationStatusStream::class

        // Use cases. LogoutUseCase wants a PlaybackStateProvider, supplied here
        // by the concrete PlaybackManager that implements it — we keep the
        // long-form factory so the type narrowing is explicit.
        factoryOf(::LoginUseCase)
        factoryOf(::RegisterUseCase)
        factoryOf(::SetupUseCase)
        factory {
            LogoutUseCase(
                authRepository = get(),
                authSession = get(),
                userRepository = get(),
                playbackStateProvider = get<PlaybackManager>(),
            )
        }
    }
