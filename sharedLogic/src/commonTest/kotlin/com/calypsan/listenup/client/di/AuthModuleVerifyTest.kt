package com.calypsan.listenup.client.di

import com.calypsan.listenup.core.SecureStorage
import com.calypsan.listenup.client.data.remote.ApiClientFactory
import com.calypsan.listenup.client.device.DeviceInfoProvider
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import com.calypsan.listenup.client.domain.repository.LibraryResetHelper
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.domain.repository.SyncRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.client.playback.PlaybackManager
import io.kotest.core.spec.style.FunSpec
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.test.verify.verify

/**
 * Leaf verify for [clientAuthModule]. Per the architecture rubric every leaf
 * Koin module is covered by a `module.verify()` test in commonTest — this is
 * the auth module's. The whitelist enumerates dependencies the auth bindings
 * pull in but other modules own:
 *
 *  - [SecureStorage] — owned by `platformStorageModule` (token persistence).
 *  - [ServerConfig] — owned by `dataModule` (segregated interface bound to
 *    SettingsRepositoryImpl). Auth uses it for current-URL reads.
 *  - [InstanceRepository] — owned by `repositoryModule` (server-status checks).
 *  - [ApiClientFactory] — owned by `networkModule` (the bearer-equipped
 *    HttpClient). Auth's RPC factory + SSE stream depend on it.
 *  - [UserRepository] — owned by `repositoryModule` (login persists user).
 *  - [PlaybackManager] — owned by the platform playback module
 *    (LogoutUseCase needs it as a `PlaybackStateProvider` to halt playback
 *    on logout).
 *  - [LibraryResetHelper] — owned by `libraryModule` (LogoutUseCase clears
 *    library data, including pending operations, on sign-out).
 *  - [DeviceInfoProvider] — owned by the per-platform playback/platform module
 *    (login/register/setup/claim send the device's structured metadata).
 *  - [kotlinx.coroutines.CoroutineScope] — the `appScope` owned by `appCoreModule`;
 *    AuthSessionStore observes the registration-policy stream on it.
 */
@OptIn(KoinExperimentalAPI::class)
class AuthModuleVerifyTest :
    FunSpec({

        test("clientAuthModule wires up against its declared external dependencies") {
            clientAuthModule.verify(
                extraTypes =
                    listOf(
                        SecureStorage::class,
                        ServerConfig::class,
                        InstanceRepository::class,
                        ApiClientFactory::class,
                        UserRepository::class,
                        PlaybackManager::class,
                        DeviceInfoProvider::class,
                        SyncRepository::class,
                        LibraryResetHelper::class,
                        com.calypsan.listenup.client.data.remote.RpcCacheInvalidator::class,
                        kotlinx.coroutines.CoroutineScope::class,
                    ),
            )
        }
    })
