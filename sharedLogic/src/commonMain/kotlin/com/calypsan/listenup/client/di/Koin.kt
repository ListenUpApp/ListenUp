
package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.data.local.db.platformDatabaseModule
import com.calypsan.listenup.client.data.remote.ABSImportApi
import com.calypsan.listenup.client.data.remote.ABSImportApiContract
import com.calypsan.listenup.client.data.remote.AdminSettingsRpcFactory
import com.calypsan.listenup.client.data.remote.AdminUserRpcFactory
import com.calypsan.listenup.client.data.remote.KtorAdminSettingsRpcFactory
import com.calypsan.listenup.client.data.remote.KtorAdminUserRpcFactory
import com.calypsan.listenup.client.data.remote.BackupApi
import com.calypsan.listenup.client.data.remote.BackupApiContract
import com.calypsan.listenup.client.data.remote.BackupRpcFactory
import com.calypsan.listenup.client.data.remote.ImportRpcFactory
import com.calypsan.listenup.client.data.remote.KtorBackupRpcFactory
import com.calypsan.listenup.client.data.remote.KtorImportRpcFactory
import com.calypsan.listenup.client.data.remote.ActivityRpcFactory
import com.calypsan.listenup.client.data.remote.ImageApi
import com.calypsan.listenup.client.data.remote.ImageApiContract
import com.calypsan.listenup.client.data.remote.KtorActivityRpcFactory
import com.calypsan.listenup.client.data.remote.KtorSocialRpcFactory
import com.calypsan.listenup.client.data.remote.SocialRpcFactory
import com.calypsan.listenup.client.data.remote.KtorLibraryAdminRpcFactory
import com.calypsan.listenup.client.data.remote.LibraryAdminRpcFactory
import com.calypsan.listenup.client.data.remote.KtorProfileRpcFactory
import com.calypsan.listenup.client.data.remote.ProfileRpcFactory
import com.calypsan.listenup.client.data.repository.avatarUploaderOf
import com.calypsan.listenup.client.data.remote.SearchApi
import com.calypsan.listenup.client.data.remote.SearchApiContract
import com.calypsan.listenup.client.data.remote.StatsApi
import com.calypsan.listenup.client.data.remote.StatsApiContract
import com.calypsan.listenup.client.data.remote.SyncApi
import com.calypsan.listenup.client.data.remote.SyncApiContract
import com.calypsan.listenup.client.data.remote.UserPreferencesApi
import com.calypsan.listenup.client.data.remote.UserPreferencesApiContract
import com.calypsan.listenup.client.data.repository.ActiveSessionRepositoryImpl
import com.calypsan.listenup.client.data.repository.ActivityRepositoryImpl
import com.calypsan.listenup.client.data.repository.AdminRepositoryImpl
import com.calypsan.listenup.client.data.repository.AvatarDownloadRepositoryImpl
import com.calypsan.listenup.client.data.repository.CoverDownloadRepositoryImpl
import com.calypsan.listenup.client.data.repository.EventStreamRepositoryImpl
import com.calypsan.listenup.client.data.repository.HomeRepositoryImpl
import com.calypsan.listenup.client.data.repository.ImageRepositoryImpl
import com.calypsan.listenup.client.data.repository.ListeningEventRepositoryImpl
import com.calypsan.listenup.client.data.repository.LeaderboardRepositoryImpl
import com.calypsan.listenup.client.data.repository.PlaybackPositionRepositoryImpl
import com.calypsan.listenup.client.data.repository.ProfileEditRepositoryImpl
import com.calypsan.listenup.client.data.repository.ProfileRepositoryImpl
import com.calypsan.listenup.client.data.repository.SearchRepositoryImpl
import com.calypsan.listenup.client.data.repository.BookReadersRepositoryImpl
import com.calypsan.listenup.client.data.repository.StatsRepositoryImpl
import com.calypsan.listenup.client.data.repository.SyncRepositoryImpl
import com.calypsan.listenup.client.data.repository.BackupRepositoryImpl
import com.calypsan.listenup.client.data.repository.ImportRepositoryImpl
import com.calypsan.listenup.client.data.repository.UserProfileRepositoryImpl
import com.calypsan.listenup.client.data.repository.UserRepositoryImpl
import com.calypsan.listenup.client.data.sync.FtsPopulator
import com.calypsan.listenup.client.data.sync.FtsPopulatorContract
import com.calypsan.listenup.client.data.sync.ImageDownloader
import com.calypsan.listenup.client.data.sync.ImageDownloaderContract
import com.calypsan.listenup.client.data.sync.LibraryResetHelper
import com.calypsan.listenup.client.data.sync.LibraryResetHelperContract
import com.calypsan.listenup.client.domain.repository.ActiveSessionRepository
import com.calypsan.listenup.client.domain.repository.ActivityRepository
import com.calypsan.listenup.client.domain.repository.AdminRepository
import com.calypsan.listenup.client.domain.repository.AvatarDownloadRepository
import com.calypsan.listenup.client.domain.repository.CoverDownloadRepository
import com.calypsan.listenup.client.domain.repository.HomeRepository
import com.calypsan.listenup.client.domain.repository.ListeningEventRepository
import com.calypsan.listenup.client.domain.repository.PlaybackPositionRepository
import com.calypsan.listenup.client.domain.repository.ProfileRepository
import com.calypsan.listenup.client.domain.repository.SearchRepository
import com.calypsan.listenup.client.domain.repository.BookReadersRepository
import com.calypsan.listenup.client.domain.repository.StatsRepository
import com.calypsan.listenup.client.domain.repository.SyncRepository
import com.calypsan.listenup.client.domain.repository.BackupRepository
import com.calypsan.listenup.client.domain.repository.ImportRepository
import com.calypsan.listenup.client.domain.repository.UserProfileRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.client.domain.usecase.activity.FetchActivitiesUseCase
import com.calypsan.listenup.client.domain.usecase.admin.ApproveUserUseCase
import com.calypsan.listenup.client.domain.usecase.admin.CreateInviteUseCase
import com.calypsan.listenup.client.domain.usecase.admin.DeleteUserUseCase
import com.calypsan.listenup.client.domain.usecase.admin.DenyUserUseCase
import com.calypsan.listenup.client.domain.usecase.admin.GetRegistrationPolicyUseCase
import com.calypsan.listenup.client.domain.usecase.admin.LoadInvitesUseCase
import com.calypsan.listenup.client.domain.usecase.admin.LoadPendingUsersUseCase
import com.calypsan.listenup.client.domain.usecase.admin.LoadServerSettingsUseCase
import com.calypsan.listenup.client.domain.usecase.admin.LoadUsersUseCase
import com.calypsan.listenup.client.domain.usecase.admin.RevokeInviteUseCase
import com.calypsan.listenup.client.domain.usecase.admin.SetOpenRegistrationUseCase
import com.calypsan.listenup.client.domain.usecase.admin.UpdateServerSettingsUseCase
import com.calypsan.listenup.client.domain.usecase.library.RefreshLibraryUseCase
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.binds
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

/** Koin qualifier for the application-lifetime [kotlinx.coroutines.CoroutineScope]. */
private const val APP_SCOPE = "appScope"

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
 */
val useCaseModule =
    module {
        // Auth use cases are provided by clientAuthModule.

        // Library use cases (using domain layer interfaces only)
        factory {
            RefreshLibraryUseCase(
                syncRepository = get(),
            )
        }

        // Book, contributor, and series use cases are provided by bookModule,
        // contributorModule, and seriesModule respectively.

        // Shelf use cases are provided by shelfModule.

        // Admin user management use cases
        factory {
            LoadUsersUseCase(
                adminRepository = get(),
            )
        }
        factory {
            LoadPendingUsersUseCase(
                adminRepository = get(),
            )
        }
        factory {
            LoadInvitesUseCase(
                adminRepository = get(),
            )
        }
        factory {
            DeleteUserUseCase(
                adminRepository = get(),
            )
        }
        factory {
            RevokeInviteUseCase(
                adminRepository = get(),
            )
        }
        factory {
            ApproveUserUseCase(
                adminRepository = get(),
            )
        }
        factory {
            DenyUserUseCase(
                adminRepository = get(),
            )
        }
        factory {
            GetRegistrationPolicyUseCase(
                adminRepository = get(),
            )
        }
        factory {
            SetOpenRegistrationUseCase(
                adminRepository = get(),
            )
        }
        factory {
            CreateInviteUseCase(
                adminRepository = get(),
            )
        }
        factory {
            LoadServerSettingsUseCase(
                adminRepository = get(),
            )
        }
        factory {
            UpdateServerSettingsUseCase(
                adminRepository = get(),
            )
        }
        // Activity use cases
        factory {
            FetchActivitiesUseCase(
                activityRepository = get(),
            )
        }
    }

/**
 * Sync infrastructure module.
 *
 * Provides SyncManager, SyncApi, and related sync components.
 *
 * Bindings moved to dedicated modules:
 *  - [appCoreModule] — appScope CoroutineScope
 *  - [connectionModule] — RpcCacheInvalidator, ConnectionCoordinator
 */
val syncModule =
    module {
        // Sync API uses ApiClientFactory to get authenticated HttpClient at call time
        // This avoids runBlocking during DI initialization (structured concurrency)
        single<SyncApiContract> {
            SyncApi(clientFactory = get())
        }

        // Image API for downloading cover images and uploading images
        single {
            ImageApi(clientFactory = get(), serverConfig = get())
        } bind ImageApiContract::class

        // Image downloader for batch cover downloads during sync
        single {
            ImageDownloader(
                imageApi = get(),
                imageStorage = get(),
            )
        } bind ImageDownloaderContract::class

        // SearchApi for server-side search
        single<SearchApiContract> {
            SearchApi(clientFactory = get())
        }

        // GenreRpcFactory and GenreRepository are provided by genreTagModule.

        // StatsApi for listening statistics
        single {
            StatsApi(clientFactory = get())
        } bind StatsApiContract::class

        // SessionApi for reading session operations
        single {
            com.calypsan.listenup.client.data.remote
                .SessionApi(clientFactory = get())
        } bind com.calypsan.listenup.client.data.remote.SessionApiContract::class

        // BackupApi for admin backup/restore operations
        single {
            BackupApi(clientFactory = get())
        } bind BackupApiContract::class

        // ABSImportApi for persistent ABS import operations
        single {
            ABSImportApi(clientFactory = get(), errorBus = get())
        } bind ABSImportApiContract::class

        // LibraryAdminRpcFactory — kotlinx.rpc proxy for LibraryAdminService.
        single<LibraryAdminRpcFactory> {
            KtorLibraryAdminRpcFactory(
                apiClientFactory = get(),
                serverConfig = get(),
            )
        } binds arrayOf(com.calypsan.listenup.client.data.remote.RemoteCache::class)

        // ScannerRpcFactory — kotlinx.rpc proxy for ScannerService (public mount):
        // live scan-progress stream that drives the scan UI + post-scan reconcile.
        single<com.calypsan.listenup.client.data.remote.ScannerRpcFactory> {
            com.calypsan.listenup.client.data.remote.KtorScannerRpcFactory(
                apiClientFactory = get(),
                serverConfig = get(),
            )
        } binds arrayOf(com.calypsan.listenup.client.data.remote.RemoteCache::class)

        // MetadataLookupRpcFactory is provided by bookModule (R3: metadata travels with book).

        // AdminUserRpcFactory — kotlinx.rpc proxy for AdminUserService (user roster, approval queue, edits).
        single<AdminUserRpcFactory> {
            KtorAdminUserRpcFactory(
                apiClientFactory = get(),
                serverConfig = get(),
            )
        } binds arrayOf(com.calypsan.listenup.client.data.remote.RemoteCache::class)

        // AdminSettingsRpcFactory — kotlinx.rpc proxy for AdminSettingsService (server identity settings).
        single<AdminSettingsRpcFactory> {
            KtorAdminSettingsRpcFactory(
                apiClientFactory = get(),
                serverConfig = get(),
            )
        } binds arrayOf(com.calypsan.listenup.client.data.remote.RemoteCache::class)

        // TagRpcFactory and TagRepository are provided by genreTagModule.

        // BackupRpcFactory — kotlinx.rpc proxy for BackupService (admin backup/restore over RPC).
        single<BackupRpcFactory> {
            KtorBackupRpcFactory(
                apiClientFactory = get(),
                serverConfig = get(),
            )
        } binds arrayOf(com.calypsan.listenup.client.data.remote.RemoteCache::class)

        // ImportRpcFactory — kotlinx.rpc proxy for ImportService (admin Audiobookshelf import over RPC).
        single<ImportRpcFactory> {
            KtorImportRpcFactory(
                apiClientFactory = get(),
                serverConfig = get(),
            )
        } binds arrayOf(com.calypsan.listenup.client.data.remote.RemoteCache::class)

        // CollectionRpcFactory, CollectionRepository, CollectionInboxApi, and InboxRepository
        // are provided by collectionModule.

        // MetadataRepository is provided by bookModule (R3: metadata travels with book).

        // ImageRepositoryImpl — one concrete instance bound to both interfaces
        single {
            ImageRepositoryImpl(
                imageDownloader = get(),
                imageStorage = get(),
                imageApi = get(),
                appScope =
                    get(
                        qualifier =
                            org.koin.core.qualifier
                                .named(APP_SCOPE),
                    ),
            )
        }
        single<com.calypsan.listenup.client.domain.repository.ImageRepository> { get<ImageRepositoryImpl>() }
        single<com.calypsan.listenup.client.domain.repository.ImageStagingRepository> { get<ImageRepositoryImpl>() }

        // DownloadRepository — read-side of download state. Per-book commands still live
        // on DownloadService; W8 will consolidate.
        single<com.calypsan.listenup.client.domain.repository.DownloadRepository> {
            com.calypsan.listenup.client.data.repository.DownloadRepositoryImpl(
                downloadDao = get(),
                bookRepository = get(),
                enqueuer = get(),
            )
        }

        // EventStreamRepository for real-time events (SOLID: interface in domain, impl in data)
        single<com.calypsan.listenup.client.domain.repository.EventStreamRepository> {
            EventStreamRepositoryImpl()
        }

        // AdminInboxApi is provided by collectionModule.
        // ShelfRpcFactory is provided by shelfModule.

        // UserPreferencesApi for syncing user preferences across devices
        single {
            UserPreferencesApi(clientFactory = get())
        } bind UserPreferencesApiContract::class

        // SocialRpcFactory — kotlinx.rpc proxy for SocialService (Room reads; RPC mutations).
        single<SocialRpcFactory> {
            KtorSocialRpcFactory(
                apiClientFactory = get(),
                serverConfig = get(),
            )
        } binds arrayOf(com.calypsan.listenup.client.data.remote.RemoteCache::class)

        // ActivityRpcFactory — kotlinx.rpc proxy for ActivityService (the social activity feed).
        single<ActivityRpcFactory> {
            KtorActivityRpcFactory(apiClientFactory = get(), serverConfig = get())
        } binds arrayOf(com.calypsan.listenup.client.data.remote.RemoteCache::class)

        // FtsPopulator for rebuilding FTS tables after sync
        single {
            FtsPopulator(
                bookDao = get(),
                contributorDao = get(),
                seriesDao = get(),
                searchDao = get(),
            )
        } bind FtsPopulatorContract::class

        // CoverDownloadRepository - owns scope for fire-and-forget cover downloads
        single<CoverDownloadRepository> {
            CoverDownloadRepositoryImpl(
                imageDownloader = get(),
                bookDao = get(),
                scope =
                    get(
                        qualifier =
                            org.koin.core.qualifier
                                .named(APP_SCOPE),
                    ),
            )
        }

        // AvatarDownloadRepository - owns scope for fire-and-forget avatar downloads (mirrors CoverDownloadRepository)
        single<AvatarDownloadRepository> {
            AvatarDownloadRepositoryImpl(
                imageDownloader = get(),
                scope =
                    get(
                        qualifier =
                            org.koin.core.qualifier
                                .named(APP_SCOPE),
                    ),
            )
        }

        // Cover Download Worker — processes the persistent cover download queue
        single {
            com.calypsan.listenup.client.data.sync.CoverDownloadWorker(
                coverDownloadDao = get(),
                imageDownloader = get(),
            )
        }

        single {
            LibraryResetHelper(
                database = get(),
                transactionRunner = get(),
                librarySyncContract = get(),
            )
        } bind LibraryResetHelperContract::class

        // SearchRepository for offline-first search
        single<SearchRepository> {
            SearchRepositoryImpl(
                searchApi = get(),
                searchDao = get(),
                imageStorage = get(),
            )
        }

        // BookRpcFactory, ContributorRpcFactory, SeriesRpcFactory, BookRepository, and
        // their edit repositories are provided by bookModule, contributorModule, and seriesModule.

        // HomeRepository for Home screen data (local-first)
        single<HomeRepository> {
            HomeRepositoryImpl(
                bookRepository = get(),
                playbackPositionDao = get(),
            )
        }

        // StatsRepository for computing listening stats locally from events
        single<StatsRepository> {
            StatsRepositoryImpl(
                listeningEventDao = get(),
                userStatsDao = get(),
                genreDao = get(),
                authSession = get(),
            )
        }

        // LeaderboardRepository — Room-observed, offline-first; reads the synced
        // public_profiles roster so all users appear in each other's leaderboard.
        single<com.calypsan.listenup.client.domain.repository.LeaderboardRepository> {
            LeaderboardRepositoryImpl(publicProfileDao = get())
        }

        // ListeningEventRepository — transactional write (upsert + pending-op) + DAO read surface.
        // TODO(P2-session): Replace userId placeholder with the authenticated user ID from the
        //  active session once the P2 session-context DI binding lands. For now we use the
        //  deviceId as a stable surrogate so that existing events can be distinguished by device.
        single<ListeningEventRepository> {
            ListeningEventRepositoryImpl(
                listeningEventDao = get(),
                transactionRunner = get(),
                userId = get(qualifier = named("deviceId")),
                tz =
                    kotlinx.datetime.TimeZone
                        .currentSystemDefault()
                        .id,
                deviceLabel = null,
            )
        }

        // BookEditRepository, SeriesEditRepository, ContributorEditRepository are provided
        // by bookModule, seriesModule, and contributorModule respectively.

        // ProfileRpcFactory — kotlinx.rpc proxy for ProfileService (RPC mutations).
        single<ProfileRpcFactory> {
            KtorProfileRpcFactory(
                apiClientFactory = get(),
                serverConfig = get(),
            )
        } binds arrayOf(com.calypsan.listenup.client.data.remote.RemoteCache::class)

        // RpcCacheInvalidator and ConnectionCoordinator are provided by connectionModule.

        // ProfileEditRepository for profile editing operations (RPC-dispatched mutations).
        single<com.calypsan.listenup.client.domain.repository.ProfileEditRepository> {
            ProfileEditRepositoryImpl(
                userDao = get(),
                profileRpcFactory = get(),
                avatarUploader = avatarUploaderOf(get()),
            )
        }

        // UserRepository for current user profile data (SOLID: interface in domain, impl in data)
        single<UserRepository> {
            UserRepositoryImpl(userDao = get(), authRpcFactory = get())
        }

        // UserProfileRepository for other users' profile data (avatars in activity feed, etc.)
        single<UserProfileRepository> {
            UserProfileRepositoryImpl(userProfileDao = get())
        }

        // AuthRepository and RegistrationStatusStream are provided by clientAuthModule.

        // SyncRepository for library sync operations (SOLID: interface in domain, impl in data)
        single<SyncRepository> {
            SyncRepositoryImpl(
                syncEngine = get(),
                syncEngineState = get(),
                authSession = get(),
                listeningEventRecorder = get(),
                scannerRpcFactory = get(),
                bookDao = get(),
                scope =
                    get(
                        qualifier =
                            org.koin.core.qualifier
                                .named(APP_SCOPE),
                    ),
            )
        }

        // PlaybackPositionRepository for position tracking (SOLID: interface in domain, impl in data)
        single<PlaybackPositionRepository> {
            PlaybackPositionRepositoryImpl(
                dao = get(),
                transactionRunner = get(),
                pendingQueue = get(),
                authSession = get(),
            )
        }

        // TagRepository is provided by genreTagModule.

        // BackupRepository — admin backup/restore via BackupService RPC proxy.
        single<BackupRepository> {
            BackupRepositoryImpl(rpcFactory = get())
        }

        // ImportRepository — admin Audiobookshelf import via ImportService RPC proxy.
        single<ImportRepository> {
            ImportRepositoryImpl(rpcFactory = get())
        }

        // GenreRepository is provided by genreTagModule.
        // ShelfRepository is provided by shelfModule.
        // CollectionRepository and InboxRepository are provided by collectionModule.

        // ActivityRepository for activity feed (SOLID: interface in domain, impl in data)
        single<ActivityRepository> {
            ActivityRepositoryImpl(dao = get(), activityRpc = get(), bookDao = get())
        }

        // ActiveSessionRepository for live sessions — SocialService RPC + local-Room book enrich,
        // re-fetched on every PresenceRefreshSignal ping (server nudge or firehose reconnect).
        single<ActiveSessionRepository> {
            ActiveSessionRepositoryImpl(
                socialRpc = get(),
                bookDao = get(),
                imageStorage = get(),
                presence = get(),
            )
        }

        // AdminRepository for admin operations (SOLID: interface in domain, impl in data)
        single<AdminRepository> {
            AdminRepositoryImpl(
                adminUserRpc = get(),
                adminSettingsRpc = get(),
                inviteRpc = get(),
                libraryAdminRpc = get(),
                serverConfig = get(),
            )
        }

        // ProfileRepository for public user profiles (SOLID: interface in domain, impl in data)
        single<ProfileRepository> {
            ProfileRepositoryImpl(
                profileRpcFactory = get(),
                userDao = get(),
                userProfileDao = get(),
                avatarDownloadRepository = get(),
            )
        }

        // UserPreferencesRepository for syncing user preferences across devices
        single<com.calypsan.listenup.client.domain.repository.UserPreferencesRepository> {
            com.calypsan.listenup.client.data.repository.UserPreferencesRepositoryImpl(
                userPreferencesApi = get(),
            )
        }

        // BookReadersRepository for Book Detail Readers section — combines the current user's local
        // reading state with other live listeners (SocialService RPC, ACL-filtered, caller-excluded,
        // re-fetched on every PresenceRefreshSignal ping).
        single<BookReadersRepository> {
            BookReadersRepositoryImpl(
                socialRpc = get(),
                presence = get(),
                playbackPositionRepository = get(),
                userRepository = get(),
            )
        }

        // ContributorRepository and SeriesRepository are provided by contributorModule and seriesModule.

        // SyncStatusRepository for sync timestamp tracking (SOLID: interface in domain, impl in data)
        single<com.calypsan.listenup.client.domain.repository.SyncStatusRepository> {
            com.calypsan.listenup.client.data.repository
                .SyncStatusRepositoryImpl()
        }

        // PendingOperationRepository (domain) for UI observation of sync status
        // Wraps the data layer contract to provide domain models to ViewModels
        single<com.calypsan.listenup.client.domain.repository.PendingOperationRepository> {
            com.calypsan.listenup.client.data.repository
                .PendingOperationRepositoryImpl()
        }

        // LibraryRepository — observation-only Room-backed view of the libraries domain
        single<com.calypsan.listenup.client.domain.repository.LibraryRepository> {
            com.calypsan.listenup.client.data.repository.LibraryRepositoryImpl(
                libraryDao = get<com.calypsan.listenup.client.data.local.db.ListenUpDatabase>().libraryDao(),
                libraryFolderDao = get<com.calypsan.listenup.client.data.local.db.ListenUpDatabase>().libraryFolderDao(),
            )
        }
    }

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
