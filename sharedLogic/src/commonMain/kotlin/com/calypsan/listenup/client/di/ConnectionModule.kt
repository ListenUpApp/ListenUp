package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.data.remote.InstanceRpcFactory
import com.calypsan.listenup.client.data.remote.KtorInstanceRpcFactory
import com.calypsan.listenup.client.data.remote.RpcCacheInvalidator
import com.calypsan.listenup.client.data.repository.InstanceRepositoryImpl
import com.calypsan.listenup.client.data.repository.ServerRepositoryImpl
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import com.calypsan.listenup.client.domain.repository.LocalPreferences
import com.calypsan.listenup.client.domain.repository.ServerRepository
import com.calypsan.listenup.client.domain.usecase.GetInstanceUseCase
import com.calypsan.listenup.core.ServerUrl
import org.koin.core.module.Module
import org.koin.core.module.dsl.factoryOf
import org.koin.core.qualifier.named
import org.koin.dsl.module

/** Koin qualifier for the application-lifetime [kotlinx.coroutines.CoroutineScope]. */
private const val APP_SCOPE = "appScope"

/**
 * Connection-coordination bindings — [InstanceRepository], [ServerRepository],
 * [GetInstanceUseCase], the [RpcCacheInvalidator] sweep, and the [ConnectionCoordinator]
 * that wires them all together at startup.
 */
internal val connectionModule: Module =
    module {
        // InstanceRepository reads server URL directly from SecureStorage to avoid circular
        // dependency (SettingsRepository -> InstanceRepository -> SettingsRepository).
        // The URL is stored before checkServerStatus() is called.
        single<InstanceRpcFactory> { KtorInstanceRpcFactory() }
        single<InstanceRepository> {
            val secureStorage: com.calypsan.listenup.core.SecureStorage = get()
            InstanceRepositoryImpl(
                getServerUrl = {
                    secureStorage.read("server_url")?.let { ServerUrl(it) }
                },
                instanceRpcFactory = get(),
                persistRemoteUrl = { url ->
                    if (url != null) {
                        secureStorage.save("server_remote_url", url)
                    } else {
                        secureStorage.delete("server_remote_url")
                    }
                },
                // Seeds the peer version from the pre-auth ServerInfo probe — see
                // InstanceRepositoryImpl's KDoc. Same eager `get()` cycle analysis as
                // networkModule's ApiClientFactory binding: LocalPreferences has no dependency
                // back on InstanceRepository.
                persistPeerVersion = get<LocalPreferences>()::setPeerServerVersion,
            )
        }

        // ServerRepository - maps live mDNS discovery into the server picker list.
        single<ServerRepository> {
            ServerRepositoryImpl(discoveryService = get())
        }

        factoryOf(::GetInstanceUseCase)

        // Aggregates every RemoteCache (RPC factories + the shared ApiClientFactory)
        // so logout / user-switch / server-URL change can drop them all in one sweep.
        // The cache set is assembled automatically via getAll() from every single bound
        // with `binds arrayOf(RemoteCache::class)` — no list to maintain.
        single<RpcCacheInvalidator> {
            com.calypsan.listenup.client.data.remote.DefaultRpcCacheInvalidator(
                caches = getAll(),
            )
        }

        // ConnectionCoordinator — drops all cached connections whenever the active
        // server URL's host:port changes, so every transport follows the new URL on
        // its next reconnect. Started at app launch.
        single(createdAtStart = true) {
            val coordinator =
                com.calypsan.listenup.client.data.connection.ConnectionCoordinator(
                    serverConfig = get(),
                    instanceRepository = get(),
                    discoveryService = get(),
                    networkMonitor = get(),
                    invalidator = get(),
                    scope =
                        get(
                            qualifier =
                                named(APP_SCOPE),
                        ),
                    engineState = get(),
                )
            coordinator.start()
            coordinator
        }
    }
