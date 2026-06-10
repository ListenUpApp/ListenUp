
package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.data.local.db.platformDatabaseModule
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Platform-specific storage module.
 * Each platform provides SecureStorage implementation via this module.
 */
expect val platformStorageModule: Module

/**
 * Platform-specific discovery module.
 * Each platform provides mDNS/Bonjour discovery implementation.
 */
expect val platformDiscoveryModule: Module

/**
 * Platform-specific device detection module.
 * Each platform provides DeviceContextProvider implementation.
 */
expect val platformDeviceModule: Module

/**
 * Data layer dependencies.
 * Provides repositories for settings and domain data.
 *
 * Bindings moved to dedicated modules:
 *  - [appCoreModule] — ErrorBus, DeepLinkManager, ShortcutActionManager, appScope CoroutineScope
 *  - [settingsModule] — SettingsRepositoryImpl + segregated interface binds
 */
val dataModule = module { }

// networkModule is defined in NetworkModule.kt — relocated wholesale to avoid a
// top-level `val networkModule` name collision between this file and that file.

/**
 * Platform-specific base URL for the API.
 * - Android emulator: 10.0.2.2 (maps to host's localhost)
 * - iOS simulator: localhost/127.0.0.1
 * - Physical devices: Use your computer's LAN IP
 */
expect fun getBaseUrl(): String

/**
 * Repository layer dependencies.
 * Binds repository interfaces to their implementations.
 *
 * Bindings moved to dedicated modules:
 *  - [persistenceModule] — all DAO providers + TransactionRunner
 *  - [connectionModule] — InstanceRpcFactory, InstanceRepository, ServerRepository
 */
val repositoryModule = module { }

/**
 * Use case layer dependencies.
 * Creates use case instances for business logic.
 *
 * Bindings moved to dedicated modules:
 *  - [connectionModule] — GetInstanceUseCase
 *  - [adminModule] — admin use cases (LoadUsersUseCase etc.)
 *  - [socialModule] — FetchActivitiesUseCase
 *  - [libraryModule] — RefreshLibraryUseCase
 */
val useCaseModule = module { }

/**
 * Sync infrastructure module.
 *
 * Provides SyncManager, SyncApi, and related sync components.
 *
 * Bindings moved to dedicated modules:
 *  - [appCoreModule] — appScope CoroutineScope
 *  - [connectionModule] — RpcCacheInvalidator, ConnectionCoordinator
 *  - [adminModule] — BackupApi, ABSImportApi, LibraryAdminRpcFactory, AdminUserRpcFactory,
 *    AdminSettingsRpcFactory, BackupRpcFactory, ImportRpcFactory, BackupRepository,
 *    ImportRepository, AdminRepository, EventStreamRepository
 *  - [socialModule] — SessionApi, SocialRpcFactory, ActivityRpcFactory, ProfileRpcFactory,
 *    ProfileEditRepository, UserRepository, UserProfileRepository, LeaderboardRepository,
 *    ActivityRepository, ActiveSessionRepository, ProfileRepository, BookReadersRepository
 *  - [listeningModule] — StatsApi, StatsRepository, ListeningEventRepository,
 *    PlaybackPositionRepository
 *  - [libraryModule] — SyncApi, UserPreferencesApi, LibraryResetHelper, ScannerRpcFactory,
 *    HomeRepository, SyncRepository, UserPreferencesRepository, SyncStatusRepository,
 *    PendingOperationRepository, LibraryRepository, RefreshLibraryUseCase
 */
val syncModule = module { }

/**
 * All shared modules that should be loaded in both Android and iOS.
 */
val sharedModules =
    listOf(
        platformStorageModule,
        platformDatabaseModule,
        platformDiscoveryModule,
        platformDeviceModule,
        appCoreModule,
        settingsModule,
        networkModule,
        persistenceModule,
        connectionModule,
        dataModule,
        repositoryModule,
        useCaseModule,
        syncModule,
        bookModule,
        contributorModule,
        seriesModule,
        collectionModule,
        shelfModule,
        genreTagModule,
        searchModule,
        mediaModule,
        adminModule,
        socialModule,
        listeningModule,
        libraryModule,
        clientSyncRenovationModule,
        clientAuthModule,
        voiceModule,
    ) + allPresentationModules

/**
 * Platform-specific initialization function.
 * Each platform (Android/iOS) implements this to set up Koin appropriately.
 *
 * @param additionalModules Platform-specific modules to include
 */
expect fun initializeKoin(additionalModules: List<Module> = emptyList())
