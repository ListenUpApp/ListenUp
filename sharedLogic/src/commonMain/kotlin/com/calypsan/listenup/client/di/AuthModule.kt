package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.data.remote.AuthRpcFactory
import com.calypsan.listenup.client.data.remote.InviteRpcFactory
import com.calypsan.listenup.client.data.remote.KtorAuthRpcFactory
import com.calypsan.listenup.client.data.remote.KtorInviteRpcFactory
import com.calypsan.listenup.client.data.repository.AuthRepositoryImpl
import com.calypsan.listenup.client.data.repository.AuthSessionStore
import com.calypsan.listenup.client.data.repository.InviteRepositoryImpl
import com.calypsan.listenup.client.data.repository.RegistrationPolicyStreamImpl
import com.calypsan.listenup.client.data.repository.RegistrationStatusStreamImpl
import com.calypsan.listenup.client.domain.repository.AuthRepository
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.InviteRepository
import com.calypsan.listenup.client.domain.repository.RegistrationPolicyStream
import com.calypsan.listenup.client.domain.repository.RegistrationStatusStream
import com.calypsan.listenup.client.domain.usecase.auth.LoginUseCase
import com.calypsan.listenup.client.domain.usecase.auth.LogoutUseCase
import com.calypsan.listenup.client.domain.usecase.auth.RegisterUseCase
import com.calypsan.listenup.client.domain.usecase.auth.SetupUseCase
import com.calypsan.listenup.client.playback.PlaybackManager
import org.koin.core.module.Module
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.binds
import org.koin.dsl.module

private const val APP_SCOPE = "appScope"

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
 *  - The public invite-claim vertical ([InviteRpcFactory] / [InviteRepository])
 *    lives here because a successful claim calls `AuthSession.saveAuthTokens`,
 *    landing the user logged-in exactly like login does.
 */
internal val clientAuthModule: Module
    // Defined as a getter (not a backing field) so each access produces a fresh
    // [Module] with its own instance cache. Koin 4 caches singletons on the
    // `InstanceFactory` instances inside the [Module], not on the [Koin]
    // container — so reusing a single [Module] value across multiple
    // `koinApplication { }` scopes (as F12's per-test fixtures need to) would
    // share singletons across scopes. Production wires Koin once at startup,
    // so the getter cost is paid once there too.
    get() =
        module {
            // Token storage + AuthState derivation. AuthSessionStore depends on
            // ServerConfig (to read the URL during state derivation); the settings
            // impl in `dataModule` depends back on AuthSession (set-URL/disconnect
            // updates auth state). The cycle is broken by injecting AuthSession as
            // Lazy<AuthSession> in the SettingsRepositoryImpl constructor, so the
            // AuthSession reference is deferred until first suspend-method use.
            // See the SettingsRepositoryImpl binding comment in Koin.kt.
            //
            // The appScope drives the login-screen registration-policy observation (live Sign Up
            // toggle); explicit single because that scope is a named dependency.
            single {
                val scope = this
                AuthSessionStore(
                    secureStorage = get(),
                    serverConfig = get(),
                    instanceRepository = get(),
                    // Lazy — breaks the cycle: RegistrationPolicyStream → ApiClientFactory → AuthSession.
                    policyStream = lazy { scope.get<RegistrationPolicyStream>() },
                    scope = scope.get(qualifier = named(APP_SCOPE)),
                )
            } bind AuthSession::class

            // kotlinx.rpc proxies for AuthServicePublic + AuthServiceAuthed.
            // Cached per mount; invalidated alongside ApiClientFactory whenever
            // the underlying HttpClient is recycled (server URL change). The
            // The `binds arrayOf(RemoteCache::class)` declaration registers these with the
            // RpcCacheInvalidator sweep automatically via getAll().
            single<AuthRpcFactory> {
                KtorAuthRpcFactory(
                    apiClientFactory = get(),
                    serverConfig = get(),
                )
            } binds arrayOf(com.calypsan.listenup.client.data.remote.RemoteCache::class)

            // Thin RPC adapter — translates contract calls into typed AppResult.
            singleOf(::AuthRepositoryImpl) bind AuthRepository::class

            // Contract-typed invite claim vertical. The RPC factory mounts the
            // public InviteServicePublic proxy; the repository lands the user
            // logged-in on a successful claim via AuthSession.saveAuthTokens.
            single<InviteRpcFactory> {
                KtorInviteRpcFactory(
                    apiClientFactory = get(),
                    serverConfig = get(),
                )
            } binds arrayOf(com.calypsan.listenup.client.data.remote.RemoteCache::class)
            singleOf(::InviteRepositoryImpl) bind InviteRepository::class

            // SSE stream for the pending-approval flow.
            singleOf(::RegistrationStatusStreamImpl) bind RegistrationStatusStream::class

            // SSE stream for the live registration-policy (Sign Up toggle on the login screen).
            singleOf(::RegistrationPolicyStreamImpl) bind RegistrationPolicyStream::class

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
                    syncRepository = get(),
                    rpcCacheInvalidator = get(),
                    playbackStateProvider = get<PlaybackManager>(),
                )
            }
        }
