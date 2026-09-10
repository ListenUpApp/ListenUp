package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.data.connection.ConnectionHealthStore
import com.calypsan.listenup.client.domain.repository.AuthRepository
import com.calypsan.listenup.client.domain.repository.DownloadRepository
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import com.calypsan.listenup.client.domain.repository.LibraryPreferences
import com.calypsan.listenup.client.domain.repository.LocalPreferences
import com.calypsan.listenup.client.domain.repository.PendingOperationRepository
import com.calypsan.listenup.client.domain.repository.PushRepository
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.domain.repository.SyncRepository
import com.calypsan.listenup.client.domain.repository.UserPreferencesRepository
import com.calypsan.listenup.client.domain.usecase.auth.LogoutUseCase
import com.calypsan.listenup.client.download.DownloadFileManager
import com.calypsan.listenup.client.download.DownloadService
import com.calypsan.listenup.client.playback.PlaybackStateProvider
import com.calypsan.listenup.core.error.ErrorBus
import io.kotest.core.spec.style.FunSpec
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.test.verify.verify

/**
 * Leaf verify for [settingsPresentationModule]. Note this module is not purely ViewModels: it also
 * binds `StorageSpaceProvider` to `DownloadFileManagerStorageAdapter`, so that adapter's own
 * dependency is verified here too.
 *
 * The whitelist enumerates dependencies [settingsPresentationModule] pulls in but other modules own:
 *
 *  - [LibraryPreferences] — owned by `settingsModule`.
 *  - [LocalPreferences] — owned by `settingsModule`.
 *  - [UserPreferencesRepository] — owned by `settingsModule`.
 *  - [InstanceRepository] — owned by `connectionModule`.
 *  - [ServerConfig] — owned by `settingsModule`.
 *  - [LogoutUseCase] — owned by `authModule`.
 *  - [PushRepository] — owned by the push module.
 *  - [ErrorBus] — owned by `appCoreModule`.
 *  - [AuthRepository] — owned by `authModule` (the Devices screen's active-session list).
 *  - [PendingOperationRepository] — owned by `clientSyncModule`.
 *  - [SyncRepository] — owned by `clientSyncModule`.
 *  - [ConnectionHealthStore] — owned by `connectionModule`.
 *  - [DownloadRepository] — owned by `downloadModule`.
 *  - [DownloadService] — owned by `downloadModule`.
 *  - [DownloadFileManager] — owned by the platform download module; the adapter this module binds
 *    wraps it to satisfy `StorageSpaceProvider`.
 *  - [PlaybackStateProvider] — the concrete `PlaybackManager`, owned by `playbackModule`.
 */
@OptIn(KoinExperimentalAPI::class)
class SettingsPresentationModuleVerifyTest :
    FunSpec({

        test("settingsPresentationModule wires up against its declared external dependencies") {
            settingsPresentationModule.verify(
                extraTypes =
                    listOf(
                        LibraryPreferences::class,
                        LocalPreferences::class,
                        UserPreferencesRepository::class,
                        InstanceRepository::class,
                        ServerConfig::class,
                        LogoutUseCase::class,
                        PushRepository::class,
                        ErrorBus::class,
                        AuthRepository::class,
                        PendingOperationRepository::class,
                        SyncRepository::class,
                        ConnectionHealthStore::class,
                        DownloadRepository::class,
                        DownloadService::class,
                        DownloadFileManager::class,
                        PlaybackStateProvider::class,
                    ),
            )
        }
    })
