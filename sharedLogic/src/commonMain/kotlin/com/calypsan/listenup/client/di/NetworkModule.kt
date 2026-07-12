package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.data.remote.ApiClientFactory
import com.calypsan.listenup.client.data.remote.KtorApiClientFactory
import com.calypsan.listenup.client.data.remote.RpcAuthRecovery
import com.calypsan.listenup.client.data.remote.RpcAuthRecoveryImpl
import com.calypsan.listenup.client.domain.repository.AuthRepository
import org.koin.core.module.Module
import org.koin.dsl.binds
import org.koin.dsl.module

/**
 * Network layer dependencies — the authenticated HTTP client factory and the
 * per-RPC-channel auth-recovery seam.
 */
internal val networkModule: Module =
    module {
        // ApiClientFactory - creates authenticated HTTP clients with auto-refresh.
        //
        // The refreshAccessToken seam is a lambda that resolves AuthRepository LAZILY at
        // refresh time, breaking the construction-time cycle:
        //   AuthRepositoryImpl(authPublicChannel=RpcChannel(RpcProxyCache(apiClientFactory=ApiClientFactory(...))))
        // If we passed `authRepository = get()` here Koin would recurse during graph
        // construction. The lambda body executes on 401, by which time all three singletons
        // are constructed.
        single<ApiClientFactory> {
            KtorApiClientFactory(
                serverConfig = get(),
                authSession = get(),
                refreshAccessToken = { get<AuthRepository>().refreshAccessToken() },
            )
        } binds arrayOf(com.calypsan.listenup.client.data.remote.RemoteCache::class)

        // RpcAuthRecovery — single-flight token refresh + request-client rebuild for every authed RPC
        // channel. Injected into their RpcProxyCache so a handshake-401 heals with one refresh + one
        // retry. Same lazy AuthRepository seam as ApiClientFactory above. Public channels bypass this
        // entirely (recovery = None), which is what stops a refresh-triggered 401 from recursing.
        single<RpcAuthRecovery> {
            RpcAuthRecoveryImpl(
                authSession = get(),
                refreshAccessToken = { get<AuthRepository>().refreshAccessToken() },
                apiClientFactory = get(),
            )
        }

        // The auth/invite RPC channels are provided by clientAuthModule. They still need to be
        // invalidated alongside ApiClientFactory whenever the underlying HttpClient is recycled —
        // see the ServerRepository binding's URL-change listener.
    }
