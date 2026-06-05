
package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.device.DeviceContext
import com.calypsan.listenup.client.device.DeviceContextProvider
import com.calypsan.listenup.core.ServerUrl
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.platformDatabaseModule
import com.calypsan.listenup.client.data.remote.ActivityFeedApi
import com.calypsan.listenup.client.data.remote.ActivityFeedApiContract
import com.calypsan.listenup.client.data.remote.ABSImportApi
import com.calypsan.listenup.client.data.remote.ABSImportApiContract
import com.calypsan.listenup.client.data.remote.AdminApi
import com.calypsan.listenup.client.data.remote.AdminApiContract
import com.calypsan.listenup.client.data.remote.AdminSettingsRpcFactory
import com.calypsan.listenup.client.data.remote.AdminUserRpcFactory
import com.calypsan.listenup.client.data.remote.ApiClientFactory
import com.calypsan.listenup.client.data.remote.KtorAdminSettingsRpcFactory
import com.calypsan.listenup.client.data.remote.KtorAdminUserRpcFactory
import com.calypsan.listenup.client.data.remote.CollectionInboxApi
import com.calypsan.listenup.client.data.remote.CollectionInboxApiContract
import com.calypsan.listenup.client.data.remote.CollectionRpcFactory
import com.calypsan.listenup.client.data.remote.KtorCollectionRpcFactory
import com.calypsan.listenup.client.data.remote.BookRpcFactory
import com.calypsan.listenup.client.data.remote.InstanceRpcFactory
import com.calypsan.listenup.client.data.remote.ContributorRpcFactory
import com.calypsan.listenup.client.data.remote.KtorBookRpcFactory
import com.calypsan.listenup.client.data.remote.KtorInstanceRpcFactory
import com.calypsan.listenup.client.data.remote.KtorContributorRpcFactory
import com.calypsan.listenup.client.data.remote.KtorSeriesRpcFactory
import com.calypsan.listenup.client.data.remote.SeriesRpcFactory
import com.calypsan.listenup.client.data.sync.handlers.ContributorSyncDomainHandler
import com.calypsan.listenup.client.data.sync.handlers.SeriesSyncDomainHandler
import com.calypsan.listenup.client.data.remote.BackupApi
import com.calypsan.listenup.client.data.remote.BackupApiContract
import com.calypsan.listenup.client.data.remote.BackupRpcFactory
import com.calypsan.listenup.client.data.remote.ImportRpcFactory
import com.calypsan.listenup.client.data.remote.KtorBackupRpcFactory
import com.calypsan.listenup.client.data.remote.KtorImportRpcFactory
import com.calypsan.listenup.client.data.remote.BookApiContract
import com.calypsan.listenup.client.data.remote.ContributorApiContract
import com.calypsan.listenup.client.data.remote.GenreRpcFactory
import com.calypsan.listenup.client.data.remote.KtorGenreRpcFactory
import com.calypsan.listenup.client.data.remote.ActivityRpcFactory
import com.calypsan.listenup.client.data.remote.ImageApi
import com.calypsan.listenup.client.data.remote.ImageApiContract
import com.calypsan.listenup.client.data.remote.InstanceApiContract
import com.calypsan.listenup.client.data.remote.KtorActivityRpcFactory
import com.calypsan.listenup.client.data.remote.KtorShelfRpcFactory
import com.calypsan.listenup.client.data.remote.KtorSocialRpcFactory
import com.calypsan.listenup.client.data.remote.ShelfRpcFactory
import com.calypsan.listenup.client.data.remote.SocialRpcFactory
import com.calypsan.listenup.client.data.remote.KtorLibraryAdminRpcFactory
import com.calypsan.listenup.client.data.remote.KtorMetadataLookupRpcFactory
import com.calypsan.listenup.client.data.remote.LibraryAdminRpcFactory
import com.calypsan.listenup.client.data.remote.MetadataLookupRpcFactory
import com.calypsan.listenup.client.data.remote.KtorProfileRpcFactory
import com.calypsan.listenup.client.data.remote.ProfileRpcFactory
import com.calypsan.listenup.client.data.repository.avatarUploaderOf
import com.calypsan.listenup.client.data.remote.SearchApi
import com.calypsan.listenup.client.data.remote.SearchApiContract
import com.calypsan.listenup.client.data.remote.SeriesApiContract
import com.calypsan.listenup.client.data.remote.StatsApi
import com.calypsan.listenup.client.data.remote.StatsApiContract
import com.calypsan.listenup.client.data.remote.SyncApi
import com.calypsan.listenup.client.data.remote.SyncApiContract
import com.calypsan.listenup.client.data.remote.KtorTagRpcFactory
import com.calypsan.listenup.client.data.remote.TagRpcFactory
import com.calypsan.listenup.client.data.remote.UserPreferencesApi
import com.calypsan.listenup.client.data.remote.UserPreferencesApiContract
import com.calypsan.listenup.client.data.remote.api.ListenUpApi
import com.calypsan.listenup.client.data.repository.ActiveSessionRepositoryImpl
import com.calypsan.listenup.client.data.repository.ActivityRepositoryImpl
import com.calypsan.listenup.client.data.repository.AdminRepositoryImpl
import com.calypsan.listenup.client.data.repository.BookEditRepositoryImpl
import com.calypsan.listenup.client.data.repository.BookRepositoryImpl
import com.calypsan.listenup.client.data.repository.CollectionRepositoryImpl
import com.calypsan.listenup.client.data.repository.AvatarDownloadRepositoryImpl
import com.calypsan.listenup.client.data.repository.CoverDownloadRepositoryImpl
import com.calypsan.listenup.client.data.repository.DeepLinkManager
import com.calypsan.listenup.client.data.repository.ShortcutActionManager
import com.calypsan.listenup.client.data.repository.EventStreamRepositoryImpl
import com.calypsan.listenup.client.data.repository.GenreRepositoryImpl
import com.calypsan.listenup.client.data.repository.HomeRepositoryImpl
import com.calypsan.listenup.client.data.repository.ImageRepositoryImpl
import com.calypsan.listenup.client.data.repository.ListeningEventRepositoryImpl
import com.calypsan.listenup.client.data.repository.InstanceRepositoryImpl
import com.calypsan.listenup.client.data.repository.LeaderboardRepositoryImpl
import com.calypsan.listenup.client.data.repository.ShelfRepositoryImpl
import com.calypsan.listenup.client.data.repository.MetadataRepositoryImpl
import com.calypsan.listenup.client.data.repository.PlaybackPositionRepositoryImpl
import com.calypsan.listenup.client.data.repository.ProfileEditRepositoryImpl
import com.calypsan.listenup.client.data.repository.ProfileRepositoryImpl
import com.calypsan.listenup.client.data.repository.SearchRepositoryImpl
import com.calypsan.listenup.client.data.repository.ServerMigrationHelper
import com.calypsan.listenup.client.data.repository.ServerRepositoryImpl
import com.calypsan.listenup.client.data.repository.ServerUrlChangeListener
import com.calypsan.listenup.client.data.repository.BookReadersRepositoryImpl
import com.calypsan.listenup.client.data.repository.SettingsRepositoryImpl
import com.calypsan.listenup.client.data.repository.StatsRepositoryImpl
import com.calypsan.listenup.client.data.repository.SyncRepositoryImpl
import com.calypsan.listenup.client.data.repository.BackupRepositoryImpl
import com.calypsan.listenup.client.data.repository.ImportRepositoryImpl
import com.calypsan.listenup.client.data.repository.TagRepositoryImpl
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
import com.calypsan.listenup.client.domain.repository.AuthRepository
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.CollectionRepository
import com.calypsan.listenup.client.domain.repository.ContributorEditRepository
import com.calypsan.listenup.client.domain.repository.AvatarDownloadRepository
import com.calypsan.listenup.client.domain.repository.CoverDownloadRepository
import com.calypsan.listenup.client.domain.repository.GenreRepository
import com.calypsan.listenup.client.domain.repository.HomeRepository
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import com.calypsan.listenup.client.domain.repository.ShelfRepository
import com.calypsan.listenup.client.domain.repository.LibraryPreferences
import com.calypsan.listenup.client.domain.repository.LibrarySync
import com.calypsan.listenup.client.domain.repository.LocalPreferences
import com.calypsan.listenup.client.domain.repository.ListeningEventRepository
import com.calypsan.listenup.client.domain.repository.PlaybackPositionRepository
import com.calypsan.listenup.client.domain.repository.PlaybackPreferences
import com.calypsan.listenup.client.domain.repository.ProfileRepository
import com.calypsan.listenup.client.domain.repository.SearchRepository
import com.calypsan.listenup.client.domain.repository.SeriesEditRepository
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.domain.repository.ServerRepository
import com.calypsan.listenup.client.domain.repository.BookReadersRepository
import com.calypsan.listenup.client.domain.repository.StatsRepository
import com.calypsan.listenup.client.domain.repository.SyncRepository
import com.calypsan.listenup.client.domain.repository.BackupRepository
import com.calypsan.listenup.client.domain.repository.ImportRepository
import com.calypsan.listenup.client.domain.repository.TagRepository
import com.calypsan.listenup.client.domain.repository.UserProfileRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.client.domain.usecase.GetInstanceUseCase
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
import com.calypsan.listenup.client.domain.usecase.book.LoadBookForEditUseCase
import com.calypsan.listenup.client.domain.usecase.book.UpdateBookUseCase
import com.calypsan.listenup.client.domain.usecase.contributor.ApplyContributorMetadataUseCase
import com.calypsan.listenup.client.domain.usecase.contributor.DeleteContributorUseCase
import com.calypsan.listenup.client.domain.usecase.contributor.UpdateContributorUseCase
import com.calypsan.listenup.client.domain.usecase.shelf.AddBooksToShelfUseCase
import com.calypsan.listenup.client.domain.usecase.shelf.CreateShelfUseCase
import com.calypsan.listenup.client.domain.usecase.shelf.DeleteShelfUseCase
import com.calypsan.listenup.client.domain.usecase.shelf.LoadShelfDetailUseCase
import com.calypsan.listenup.client.domain.usecase.shelf.RemoveBookFromShelfUseCase
import com.calypsan.listenup.client.domain.usecase.shelf.ReorderShelfBooksUseCase
import com.calypsan.listenup.client.domain.usecase.shelf.UpdateShelfUseCase
import com.calypsan.listenup.client.domain.usecase.library.RefreshLibraryUseCase
import com.calypsan.listenup.client.domain.usecase.series.UpdateSeriesUseCase
import org.koin.core.module.Module
import org.koin.core.module.dsl.factoryOf
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.binds
import org.koin.dsl.module
import com.calypsan.listenup.client.data.repository.ContributorEditRepositoryImpl
import com.calypsan.listenup.client.data.repository.SeriesEditRepositoryImpl

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
 */
val dataModule =
    module {
        // Error bus — single instance shared by every emitter (data layer, ViewModels)
        // and the single subscriber (GlobalErrorSnackbar in AppShell).
        single {
            com.calypsan.listenup.core.error
                .ErrorBus()
        }

        // Deep link manager - singleton for handling invite deep links
        // Must be initialized before MainActivity handles intents
        single { DeepLinkManager() }

        // Shortcut action manager - singleton for handling app shortcut intents
        // Observed by navigation layer to execute shortcut actions
        single { ShortcutActionManager() }

        // AuthSession (tokens + AuthState flow) is provided by clientAuthModule.
        // SettingsRepositoryImpl depends on AuthSession, but AuthSessionStore (the
        // AuthSession impl) depends on ServerConfig, which resolves back to
        // SettingsRepositoryImpl — a construction-time cycle. The cycle is broken by
        // injecting AuthSession as Lazy<AuthSession>: the lambda body runs only on
        // first suspend-method use, by which time SettingsRepositoryImpl is fully
        // constructed and registered in the Koin graph.

        // Settings repository — everything *non-auth*: server-URL plumbing, library identity,
        // library + playback preferences, device-local UI preferences. Emits preference change
        // events for PreferencesSyncObserver (in syncModule) to consume without circular deps.
        single {
            val scope = this
            SettingsRepositoryImpl(
                secureStorage = get(),
                authSession = lazy { scope.get<AuthSession>() },
            )
        }

        // Bind the remaining segregated interfaces to the same SettingsRepositoryImpl instance.
        single<ServerConfig> { get<SettingsRepositoryImpl>() }
        single<LibrarySync> { get<SettingsRepositoryImpl>() }
        single<LibraryPreferences> { get<SettingsRepositoryImpl>() }
        single<PlaybackPreferences> { get<SettingsRepositoryImpl>() }
        single<LocalPreferences> { get<SettingsRepositoryImpl>() }
    }

/**
 * Network layer dependencies.
 * Provides HTTP clients and API configuration with authentication support.
 *
 * Note: Initial setup uses default base URL from getBaseUrl().
 * When user configures a different server URL at runtime, API instances
 * should be recreated via factory pattern or manual invalidation.
 */
val networkModule =
    module {
        // ApiClientFactory - creates authenticated HTTP clients with auto-refresh.
        //
        // The refreshAccessToken seam is a lambda that resolves AuthRepository LAZILY at
        // refresh time, breaking the construction-time cycle:
        //   AuthRepositoryImpl(rpc=AuthRpcFactory(apiClientFactory=ApiClientFactory(...)))
        // If we passed `authRepository = get()` here Koin would recurse during graph
        // construction. The lambda body executes on 401, by which time all three singletons
        // are constructed.
        single {
            ApiClientFactory(
                serverConfig = get(),
                authSession = get(),
                refreshAccessToken = { get<AuthRepository>().refreshAccessToken() },
            )
        } binds arrayOf(com.calypsan.listenup.client.data.remote.RemoteCache::class)

        // AuthRpcFactory is provided by clientAuthModule. It still needs to be
        // invalidated alongside ApiClientFactory whenever the underlying HttpClient
        // is recycled — see the ServerRepository binding's URL-change listener.

        // ListenUpApi - main API for server communication
        // Uses default base URL initially; can be recreated when server URL changes
        single {
            ListenUpApi(
                baseUrl = getBaseUrl(),
                apiClientFactory = get(),
            )
        }

        // Bind segregated interfaces to the same ListenUpApi instance (ISP compliance)
        single<InstanceApiContract> { get<ListenUpApi>() }
        single<BookApiContract> { get<ListenUpApi>() }
        single<ContributorApiContract> { get<ListenUpApi>() }
        single<SeriesApiContract> { get<ListenUpApi>() }
    }

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
 */
val repositoryModule =
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
            )
        }

        // Provide DAOs from database
        single { get<ListenUpDatabase>().userDao() }
        single { get<ListenUpDatabase>().userProfileDao() }
        single { get<ListenUpDatabase>().bookDao() }
        single { get<ListenUpDatabase>().chapterDao() }
        single { get<ListenUpDatabase>().seriesDao() }
        single { get<ListenUpDatabase>().contributorDao() }
        single { get<ListenUpDatabase>().contributorAliasDao() }
        single { get<ListenUpDatabase>().bookContributorDao() }
        single { get<ListenUpDatabase>().bookSeriesDao() }
        single { get<ListenUpDatabase>().playbackPositionDao() }
        single { get<ListenUpDatabase>().downloadDao() }
        single { get<ListenUpDatabase>().coverDownloadDao() }
        single { get<ListenUpDatabase>().searchDao() }
        single { get<ListenUpDatabase>().serverDao() }
        single { get<ListenUpDatabase>().collectionDao() }
        single { get<ListenUpDatabase>().collectionBookDao() }
        single { get<ListenUpDatabase>().collectionShareDao() }
        single { get<ListenUpDatabase>().shelfDao() }
        single { get<ListenUpDatabase>().shelfBookDao() }
        single { get<ListenUpDatabase>().tagDao() }
        single { get<ListenUpDatabase>().genreDao() }
        single { get<ListenUpDatabase>().audioFileDao() }
        single { get<ListenUpDatabase>().listeningEventDao() }
        single { get<ListenUpDatabase>().activityDao() }
        single { get<ListenUpDatabase>().userStatsDao() }
        single { get<ListenUpDatabase>().tentativeSpanDao() }
        single { get<ListenUpDatabase>().publicProfileDao() }

        single<com.calypsan.listenup.client.data.local.db.TransactionRunner> {
            com.calypsan.listenup.client.data.local.db
                .RoomTransactionRunner(get())
        }

        // ServerRepository - bridges mDNS discovery with database persistence
        // When active server's URL changes via mDNS rediscovery, updates ServerConfig
        // and invalidates the API client cache to use the new IP address.
        single<ServerRepository> {
            ServerRepositoryImpl(
                serverDao = get(),
                discoveryService = get(),
                scope =
                    get(
                        qualifier =
                            org.koin.core.qualifier
                                .named(APP_SCOPE),
                    ),
                urlChangeListener =
                    ServerUrlChangeListener { newUrl ->
                        // Update settings with the new URL, then drop every remote cache that
                        // captured the old HttpClient — all of them, via the aggregate, so no
                        // authed proxy is left pointing at the stale host.
                        val serverConfig: ServerConfig = get()
                        serverConfig.setServerUrl(newUrl)
                        get<com.calypsan.listenup.client.data.remote.RpcCacheInvalidator>()
                            .invalidateAll()
                    },
            )
        }

        // ServerMigrationHelper - migrates legacy single-server data
        single {
            ServerMigrationHelper(
                secureStorage = get(),
                serverDao = get(),
            )
        }
    }

/**
 * Use case layer dependencies.
 * Creates use case instances for business logic.
 */
val useCaseModule =
    module {
        factoryOf(::GetInstanceUseCase)

        // Auth use cases are provided by clientAuthModule.

        // Library use cases (using domain layer interfaces only)
        factory {
            RefreshLibraryUseCase(
                syncRepository = get(),
            )
        }

        // Book use cases (using domain layer interfaces only)
        factory {
            LoadBookForEditUseCase(
                bookRepository = get(),
                genreRepository = get(),
                tagRepository = get(),
            )
        }
        factory {
            UpdateBookUseCase(
                bookEditRepository = get(),
                tagRepository = get(),
                imageRepository = get(),
                imageStagingRepository = get(),
            )
        }
        // Contributor use cases
        factory {
            UpdateContributorUseCase(
                contributorEditRepository = get(),
            )
        }
        factory {
            DeleteContributorUseCase(
                contributorRepository = get(),
            )
        }
        factory {
            ApplyContributorMetadataUseCase(
                metadataRepository = get(),
            )
        }
        // Series use cases
        factory {
            UpdateSeriesUseCase(
                seriesEditRepository = get(),
                imageRepository = get(),
                imageStagingRepository = get(),
            )
        }
        // Shelf use cases
        factory {
            CreateShelfUseCase(
                shelfRepository = get(),
            )
        }
        factory {
            UpdateShelfUseCase(
                shelfRepository = get(),
            )
        }
        factory {
            DeleteShelfUseCase(
                shelfRepository = get(),
            )
        }
        factory {
            LoadShelfDetailUseCase(
                shelfRepository = get(),
                imageRepository = get(),
            )
        }
        factory {
            RemoveBookFromShelfUseCase(
                shelfRepository = get(),
            )
        }
        factory {
            AddBooksToShelfUseCase(
                shelfRepository = get(),
            )
        }
        factory {
            ReorderShelfBooksUseCase(
                shelfRepository = get(),
            )
        }
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
 */
val syncModule =
    module {
        // Application-scoped CoroutineScope for long-lived background operations.
        // Used by sync and playback tasks that span the app's lifetime.
        // SupervisorJob ensures child failures don't cancel siblings.
        single<kotlinx.coroutines.CoroutineScope>(
            qualifier =
                org.koin.core.qualifier
                    .named(APP_SCOPE),
        ) {
            kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
            )
        }

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

        // GenreRpcFactory — kotlinx.rpc proxy for the curator mutation surface.
        // Tree reads come from Room (via GenreDao); only mutations and the
        // unmapped-string queue need an RPC channel.
        single<GenreRpcFactory> {
            KtorGenreRpcFactory(apiClientFactory = get(), serverConfig = get())
        } binds arrayOf(com.calypsan.listenup.client.data.remote.RemoteCache::class)

        // StatsApi for listening statistics
        single {
            StatsApi(clientFactory = get())
        } bind StatsApiContract::class

        // ActivityFeedApi for social activity feed
        single {
            ActivityFeedApi(clientFactory = get())
        } bind ActivityFeedApiContract::class

        // SessionApi for reading session operations
        single {
            com.calypsan.listenup.client.data.remote
                .SessionApi(clientFactory = get())
        } bind com.calypsan.listenup.client.data.remote.SessionApiContract::class

        // AdminApi for admin operations (user/invite management)
        single {
            AdminApi(clientFactory = get())
        } bind AdminApiContract::class

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

        // MetadataLookupRpcFactory — kotlinx.rpc proxy for MetadataLookupService.
        single<MetadataLookupRpcFactory> {
            KtorMetadataLookupRpcFactory(
                apiClientFactory = get(),
                serverConfig = get(),
            )
        } binds arrayOf(com.calypsan.listenup.client.data.remote.RemoteCache::class)

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

        // TagRpcFactory — kotlinx.rpc proxy for TagService (observations from Room; mutations via RPC).
        single<TagRpcFactory> {
            KtorTagRpcFactory(
                apiClientFactory = get(),
                serverConfig = get(),
            )
        } binds arrayOf(com.calypsan.listenup.client.data.remote.RemoteCache::class)

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

        // CollectionRpcFactory — kotlinx.rpc proxy for CollectionService (Room reads; RPC mutations).
        single<CollectionRpcFactory> {
            KtorCollectionRpcFactory(
                apiClientFactory = get(),
                serverConfig = get(),
            )
        } binds arrayOf(com.calypsan.listenup.client.data.remote.RemoteCache::class)

        // MetadataRepository for metadata operations (SOLID: interface in domain, impl in data)
        single<com.calypsan.listenup.client.domain.repository.MetadataRepository> {
            MetadataRepositoryImpl(rpcFactory = get())
        }

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

        // AdminInboxApi for the 1b admin collection-inbox REST routes
        single {
            CollectionInboxApi(clientFactory = get())
        } bind CollectionInboxApiContract::class

        // UserPreferencesApi for syncing user preferences across devices
        single {
            UserPreferencesApi(clientFactory = get())
        } bind UserPreferencesApiContract::class

        // ShelfRpcFactory — kotlinx.rpc proxy for ShelfService (Room reads; RPC mutations).
        single<ShelfRpcFactory> {
            KtorShelfRpcFactory(
                apiClientFactory = get(),
                serverConfig = get(),
            )
        } binds arrayOf(com.calypsan.listenup.client.data.remote.RemoteCache::class)

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

        // BookRpcFactory - kotlinx.rpc proxy for BookService (on-demand fetch + search).
        // Mirrors AuthRpcFactory; fully functional end-to-end — the server registers
        // BookService on its bearer-gated /api/rpc/authed surface (landed in T28.5).
        single<BookRpcFactory> {
            KtorBookRpcFactory(
                apiClientFactory = get(),
                serverConfig = get(),
            )
        } binds arrayOf(com.calypsan.listenup.client.data.remote.RemoteCache::class)

        // ContributorRpcFactory - kotlinx.rpc proxy for ContributorService (cache-miss fetch).
        // Registered on the same bearer-gated /api/rpc/authed surface as BookService (Books-B2).
        single<ContributorRpcFactory> {
            KtorContributorRpcFactory(
                apiClientFactory = get(),
                serverConfig = get(),
            )
        } binds arrayOf(com.calypsan.listenup.client.data.remote.RemoteCache::class)

        // SeriesRpcFactory - kotlinx.rpc proxy for SeriesService (cache-miss fetch).
        // Registered on the same bearer-gated /api/rpc/authed surface as BookService (Books-B2).
        single<SeriesRpcFactory> {
            KtorSeriesRpcFactory(
                apiClientFactory = get(),
                serverConfig = get(),
            )
        } binds arrayOf(com.calypsan.listenup.client.data.remote.RemoteCache::class)

        // BookRepository for UI data access
        single<BookRepository> {
            BookRepositoryImpl(
                bookDao = get(),
                chapterDao = get(),
                audioFileDao = get(),
                searchDao = get(),
                transactionRunner = get(),
                imageStorage = get(),
                genreRepository = get(),
                tagRepository = get(),
                networkMonitor = get(),
                bookRpcFactory = get(),
                bookSyncDomainHandler = get(),
            )
        }

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

        // BookEditRepository — pure RPC dispatcher; SSE echoes write back into Room.
        single<com.calypsan.listenup.client.domain.repository.BookEditRepository> {
            BookEditRepositoryImpl(
                bookRpcFactory = get(),
                collectionRpcFactory = get(),
            )
        }

        // SeriesEditRepository — pure RPC dispatcher (Books-C1).
        single<SeriesEditRepository> {
            SeriesEditRepositoryImpl(
                seriesRpcFactory = get(),
            )
        }

        // ContributorEditRepository — pure RPC dispatcher (Books-C1).
        single<ContributorEditRepository> {
            ContributorEditRepositoryImpl(
                contributorRpcFactory = get(),
            )
        }

        // ProfileRpcFactory — kotlinx.rpc proxy for ProfileService (RPC mutations).
        single<ProfileRpcFactory> {
            KtorProfileRpcFactory(
                apiClientFactory = get(),
                serverConfig = get(),
            )
        } binds arrayOf(com.calypsan.listenup.client.data.remote.RemoteCache::class)

        // Aggregates every RemoteCache (RPC factories + the shared ApiClientFactory)
        // so logout / user-switch / server-URL change can drop them all in one sweep.
        // The cache set is assembled automatically via getAll() from every single bound
        // with `binds arrayOf(RemoteCache::class)` — no list to maintain.
        single<com.calypsan.listenup.client.data.remote.RpcCacheInvalidator> {
            com.calypsan.listenup.client.data.remote.DefaultRpcCacheInvalidator(
                caches = getAll(),
            )
        }

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

        // TagRepository — observations from Room, mutations via RPC (Tags phase)
        single<TagRepository> {
            TagRepositoryImpl(
                tagRpcFactory = get(),
                tagDao = get<ListenUpDatabase>().tagDao(),
                bookTagDao = get<ListenUpDatabase>().bookTagDao(),
            )
        }

        // BackupRepository — admin backup/restore via BackupService RPC proxy.
        single<BackupRepository> {
            BackupRepositoryImpl(rpcFactory = get())
        }

        // ImportRepository — admin Audiobookshelf import via ImportService RPC proxy.
        single<ImportRepository> {
            ImportRepositoryImpl(rpcFactory = get())
        }

        // GenreRepository — Room-backed reads, RPC-dispatched mutations.
        single<GenreRepository> {
            GenreRepositoryImpl(dao = get(), rpcFactory = get())
        }

        // ShelfRepository for personal curation shelves (SOLID: interface in domain, impl in data)
        single<ShelfRepository> {
            ShelfRepositoryImpl(
                dao = get(),
                userDao = get(),
                rpcFactory = get(),
            )
        }

        // CollectionRepository — Room reads + CollectionService RPC writes (interface in domain, impl in data)
        single<CollectionRepository> {
            CollectionRepositoryImpl(
                collectionDao = get(),
                collectionBookDao = get(),
                collectionShareDao = get(),
                rpcFactory = get(),
            )
        }

        // InboxRepository — admin collection-inbox over the 1b REST routes
        single<com.calypsan.listenup.client.domain.repository.InboxRepository> {
            com.calypsan.listenup.client.data.repository
                .InboxRepositoryImpl(api = get())
        }

        // ActivityRepository for activity feed (SOLID: interface in domain, impl in data)
        single<ActivityRepository> {
            ActivityRepositoryImpl(dao = get(), activityFeedApi = get())
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
                adminApi = get(),
                adminUserRpc = get(),
                adminSettingsRpc = get(),
                inviteRpc = get(),
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

        // BookReadersRepository for Book Detail Readers section — SocialService RPC, ACL-filtered
        // and caller-excluded server-side, re-fetched on every PresenceRefreshSignal ping.
        single<BookReadersRepository> {
            BookReadersRepositoryImpl(
                socialRpc = get(),
                presence = get(),
            )
        }

        // BookListeningHistoryRepository for Book Detail listening-history section.
        // Pure Room observation from listening_events, day-bucketed per the viewer's local TZ.
        single<com.calypsan.listenup.client.domain.repository.BookListeningHistoryRepository> {
            com.calypsan.listenup.client.data.repository.BookListeningHistoryRepositoryImpl(
                listeningEventDao = get(),
                authSession = get(),
            )
        }

        // ContributorRepository for domain-layer contributor queries including search and metadata
        single<com.calypsan.listenup.client.domain.repository.ContributorRepository> {
            com.calypsan.listenup.client.data.repository.ContributorRepositoryImpl(
                contributorDao = get(),
                bookDao = get(),
                searchDao = get(),
                api = get(),
                networkMonitor = get(),
                imageStorage = get(),
                rpcFactory = get(),
                contributorSyncHandler = get<ContributorSyncDomainHandler>(),
            )
        }

        // SeriesRepository for domain-layer series queries including search
        single<com.calypsan.listenup.client.domain.repository.SeriesRepository> {
            com.calypsan.listenup.client.data.repository.SeriesRepositoryImpl(
                seriesDao = get(),
                bookDao = get(),
                searchDao = get(),
                api = get(),
                networkMonitor = get(),
                imageStorage = get(),
                rpcFactory = get(),
                seriesSyncHandler = get<SeriesSyncDomainHandler>(),
            )
        }

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
        dataModule,
        networkModule,
        repositoryModule,
        useCaseModule,
        syncModule,
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
