package com.calypsan.listenup.client.di

import com.calypsan.listenup.api.LibraryAdminService
import com.calypsan.listenup.client.data.remote.rpcChannel
import com.calypsan.listenup.client.domain.usecase.auth.LogoutUseCase
import com.calypsan.listenup.client.download.DownloadFileManager
import com.calypsan.listenup.client.download.DownloadFileManagerStorageAdapter
import com.calypsan.listenup.client.download.StorageSpaceProvider
import com.calypsan.listenup.client.presentation.connection.ConnectionHealthViewModel
import com.calypsan.listenup.client.presentation.download.DownloadPhasesViewModel
import com.calypsan.listenup.client.presentation.hardcover.HardcoverMatchViewModel
import com.calypsan.listenup.client.presentation.hardcover.KeptOffBooksViewModel
import com.calypsan.listenup.client.presentation.notifications.NotificationBellViewModel
import com.calypsan.listenup.client.presentation.notifications.NotificationPrefsViewModel
import com.calypsan.listenup.client.presentation.notifications.NotificationsViewModel
import com.calypsan.listenup.client.presentation.profile.EditProfileViewModel
import com.calypsan.listenup.client.presentation.profile.UserProfileViewModel
import com.calypsan.listenup.client.presentation.settings.DevicesViewModel
import com.calypsan.listenup.client.presentation.settings.HardcoverSettingsViewModel
import com.calypsan.listenup.client.presentation.settings.SettingsViewModel
import com.calypsan.listenup.client.presentation.startup.AppStartupViewModel
import com.calypsan.listenup.client.presentation.storage.StorageViewModel
import com.calypsan.listenup.client.presentation.sync.SyncIndicatorViewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module

/** Koin qualifier for the application-lifetime [kotlinx.coroutines.CoroutineScope] (`appCoreModule`). */
private const val APP_SCOPE = "appScope"

/**
 * User profile ViewModels.
 */
internal val profilePresentationModule =
    module {
        // UserProfileViewModel for viewing user profiles
        factory {
            UserProfileViewModel(
                publicProfileDao = get(),
                shelfRepository = get(),
                userRepository = get(),
                activityRepository = get(),
            )
        }
        // EditProfileViewModel for editing own profile
        factory {
            EditProfileViewModel(
                profileEditRepository = get(),
                userRepository = get(),
                userProfileRepository = get(),
            )
        }
    }

/**
 * Notification surface ViewModels — the shell bell, the inbox list, and the Settings toggles.
 */
internal val notificationsPresentationModule =
    module {
        // Bell badge count for the shell header — factory (NOT single), scoped to the shell's
        // ViewModelStore like SyncIndicatorViewModel below.
        factory {
            NotificationBellViewModel(
                repo = get(),
            )
        }
        factory {
            NotificationsViewModel(
                repo = get(),
                errorBus = get(),
                // App-lifetime scope: a tap that marks a row read usually also navigates away, and on
                // web leaving the inbox clears its viewModelScope before the write has landed.
                appScope = get(qualifier = named(APP_SCOPE)),
            )
        }
        factory {
            NotificationPrefsViewModel(
                repo = get(),
                errorBus = get(),
            )
        }
    }

/**
 * Settings and sync indicator ViewModels.
 */
internal val settingsPresentationModule =
    module {
        factory {
            SettingsViewModel(
                libraryPreferences = get(),
                localPreferences = get(),
                userPreferencesRepository = get(),
                instanceRepository = get(),
                serverConfig = get(),
                logoutUseCase = get<LogoutUseCase>(),
                pushRepository = get(),
                hardcoverRepository = get(),
                permissionsRepository = get(),
                appVersion = get(named("clientVersion")),
                errorBus = get(),
            )
        }
        // Settings → Account → Hardcover: connect, sync, the books that need a match, disconnect.
        factory { HardcoverSettingsViewModel(repository = get(), bookRepository = get()) }
        // Settings → Account → Hardcover → Kept off Hardcover: the books kept off, each with Sync again (#1541).
        factory {
            KeptOffBooksViewModel(
                repository = get(),
                bookRepository = get(),
            )
        }
        // Find on Hardcover for one book: search, pick, or remove the match.
        factory { params ->
            HardcoverMatchViewModel(
                bookId = params.get(),
                repository = get(),
                bookRepository = get(),
                // App-lifetime scope: Undo is offered after this screen has closed, so it can't ride viewModelScope.
                appScope = get(qualifier = named(APP_SCOPE)),
                errorBus = get(),
            )
        }
        // DevicesViewModel for the Devices (active sessions) screen
        factory { DevicesViewModel(authRepository = get(), errorBus = get()) }
        // factory (NOT single) — same cancelled-viewModelScope hazard as the Library VMs above.
        factory {
            SyncIndicatorViewModel(
                pendingOperationRepository = get(),
                syncRepository = get(),
                manualSyncRefresh = get(),
            )
        }
        // Shell connection-health banner VM, projecting ConnectionHealthStore.state.
        factory { ConnectionHealthViewModel(healthStore = get()) }
        // StorageViewModel for storage management screen
        factory<StorageSpaceProvider> { DownloadFileManagerStorageAdapter(get<DownloadFileManager>()) }
        factory {
            StorageViewModel(
                downloadRepository = get(),
                downloadService = get(),
                storageSpaceProvider = get(),
                errorBus = get(),
                // Refuses deleting the playing book. Each platform's playback module binds it.
                playbackStateProvider = get(),
                inboxRepository = get(),
                localPreferences = get(),
                networkMonitor = get(),
            )
        }
        // Where every book's download stands, for the Library grid's cover badges.
        factory {
            DownloadPhasesViewModel(
                downloadRepository = get(),
                localPreferences = get(),
                networkMonitor = get(),
            )
        }
    }

/**
 * App startup / navigation initialisation ViewModel.
 */
internal val startupPresentationModule =
    module {
        factory {
            AppStartupViewModel(
                userRepository = get(),
                libraryAdminChannel = rpcChannel<LibraryAdminService>(),
                authSession = get(),
                syncRepository = get(),
            )
        }
    }
