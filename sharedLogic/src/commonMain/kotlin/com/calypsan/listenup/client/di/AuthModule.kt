package com.calypsan.listenup.client.di

import com.calypsan.listenup.api.AuthServiceAuthed
import com.calypsan.listenup.api.AuthServicePublic
import com.calypsan.listenup.api.InviteService
import com.calypsan.listenup.api.InviteServicePublic
import com.calypsan.listenup.client.data.remote.RpcPolicy
import com.calypsan.listenup.client.data.remote.rpcChannel
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
import org.koin.dsl.module

private const val APP_SCOPE = "appScope"

/**
 * Auth domain wiring — every binding the auth flow owns. Pulled out of
 * the monolithic `Koin.kt` so the auth surface is auditable in one
 * place and so `module.verify()` can run a leaf check against it.
 *
 * What lives here:
 *  - [AuthSession] / [AuthSessionStore] — token storage + auth-state flow.
 *  - The four auth/invite `rpcChannel` singles — the split AuthService + InviteService contracts.
 *  - [AuthRepository] / [AuthRepositoryImpl] — the typed `AppResult` surface.
 *  - [RegistrationStatusStream] — SSE for "is my registration approved yet?".
 *  - The four auth use cases.
 *
 * What deliberately stays in `Koin.kt`:
 *  - `ApiClientFactory` — bearer-equipped HttpClient consumed by every API,
 *    not just auth. Its refresh-callback `{ get<AuthRepository>() … }` resolves
 *    across module boundaries at runtime, which is standard Koin.
 *  - The public invite-claim vertical (the InviteServicePublic channel / [InviteRepository])
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

            // The four auth/invite RPC channels — one line per service, no factory, no
            // repository-side result fold. Each `rpcChannel` single joins the RpcCacheInvalidator
            // sweep automatically (via its `binds RemoteCache`), so all four drop on logout / URL
            // change with no call site remembering to list them.
            //
            // The Public/Authed split is the recursion firewall: the refresh primitive
            // (AuthServicePublic.refreshSession) rides the Public channel, whose recovery is None —
            // so a 401 during refresh can never trigger another refresh.
            rpcChannel<AuthServicePublic>(RpcPolicy.Public)
            rpcChannel<AuthServiceAuthed>()
            rpcChannel<InviteServicePublic>(RpcPolicy.Public)
            rpcChannel<InviteService>()

            // Thin RPC adapter — translates contract calls into typed AppResult over the two auth
            // channels (public handshake vs bearer-gated session).
            single<AuthRepository> {
                AuthRepositoryImpl(
                    authPublicChannel = rpcChannel(),
                    authedChannel = rpcChannel(),
                    authSession = get(),
                )
            }

            // Contract-typed invite claim vertical over the anonymous InviteServicePublic channel;
            // the repository lands the user logged-in on a successful claim via AuthSession.saveAuthTokens.
            single<InviteRepository> {
                InviteRepositoryImpl(
                    channel = rpcChannel(),
                    authSession = get(),
                    userRepository = get(),
                    deviceInfoProvider = get(),
                )
            }

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
