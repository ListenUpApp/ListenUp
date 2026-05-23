@file:Suppress("StringLiteralDuplication")

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
import com.calypsan.listenup.client.data.remote.AdminCollectionApi
import com.calypsan.listenup.client.data.remote.AdminCollectionApiContract
import com.calypsan.listenup.client.data.remote.ApiClientFactory
import com.calypsan.listenup.client.data.remote.AuthRpcFactory
import com.calypsan.listenup.client.data.remote.BookRpcFactory
import com.calypsan.listenup.client.data.remote.KtorBookRpcFactory
import com.calypsan.listenup.client.data.remote.BackupApi
import com.calypsan.listenup.client.data.remote.BackupApiContract
import com.calypsan.listenup.client.data.remote.BookApiContract
import com.calypsan.listenup.client.data.remote.ContributorApiContract
import com.calypsan.listenup.client.data.remote.GenreApi
import com.calypsan.listenup.client.data.remote.GenreApiContract
import com.calypsan.listenup.client.data.remote.ImageApi
import com.calypsan.listenup.client.data.remote.ImageApiContract
import com.calypsan.listenup.client.data.remote.InstanceApiContract
import com.calypsan.listenup.client.data.remote.InviteApi
import com.calypsan.listenup.client.data.remote.InviteApiContract
import com.calypsan.listenup.client.data.remote.LeaderboardApi
import com.calypsan.listenup.client.data.remote.LeaderboardApiContract
import com.calypsan.listenup.client.data.remote.ShelfApi
import com.calypsan.listenup.client.data.remote.ShelfApiContract
import com.calypsan.listenup.client.data.remote.MetadataApi
import com.calypsan.listenup.client.data.remote.MetadataApiContract
import com.calypsan.listenup.client.data.remote.ProfileApi
import com.calypsan.listenup.client.data.remote.ProfileApiContract
import com.calypsan.listenup.client.data.remote.SearchApi
import com.calypsan.listenup.client.data.remote.SearchApiContract
import com.calypsan.listenup.client.data.remote.SeriesApiContract
import com.calypsan.listenup.client.data.remote.SetupApi
import com.calypsan.listenup.client.data.remote.SetupApiContract
import com.calypsan.listenup.client.data.remote.StatsApi
import com.calypsan.listenup.client.data.remote.StatsApiContract
import com.calypsan.listenup.client.data.remote.SyncApi
import com.calypsan.listenup.client.data.remote.SyncApiContract
import com.calypsan.listenup.client.data.remote.TagApi
import com.calypsan.listenup.client.data.remote.TagApiContract
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
import com.calypsan.listenup.client.data.repository.SessionRepositoryImpl
import com.calypsan.listenup.client.data.repository.SettingsRepositoryImpl
import com.calypsan.listenup.client.data.repository.StatsRepositoryImpl
import com.calypsan.listenup.client.data.repository.SyncRepositoryImpl
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
import com.calypsan.listenup.client.domain.repository.SessionRepository
import com.calypsan.listenup.client.domain.repository.StatsRepository
import com.calypsan.listenup.client.domain.repository.SyncRepository
import com.calypsan.listenup.client.domain.repository.TagRepository
import com.calypsan.listenup.client.domain.repository.UserProfileRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.client.domain.usecase.GetInstanceUseCase
import com.calypsan.listenup.client.domain.usecase.activity.FetchActivitiesUseCase
import com.calypsan.listenup.client.domain.usecase.admin.ApproveUserUseCase
import com.calypsan.listenup.client.domain.usecase.admin.CreateInviteUseCase
import com.calypsan.listenup.client.domain.usecase.admin.DeleteUserUseCase
import com.calypsan.listenup.client.domain.usecase.admin.DenyUserUseCase
import com.calypsan.listenup.client.domain.usecase.admin.LoadInboxBooksUseCase
import com.calypsan.listenup.client.domain.usecase.admin.LoadInvitesUseCase
import com.calypsan.listenup.client.domain.usecase.admin.LoadPendingUsersUseCase
import com.calypsan.listenup.client.domain.usecase.admin.LoadServerSettingsUseCase
import com.calypsan.listenup.client.domain.usecase.admin.LoadUsersUseCase
import com.calypsan.listenup.client.domain.usecase.admin.ReleaseBooksUseCase
import com.calypsan.listenup.client.domain.usecase.admin.RevokeInviteUseCase
import com.calypsan.listenup.client.domain.usecase.admin.SetOpenRegistrationUseCase
import com.calypsan.listenup.client.domain.usecase.admin.StageCollectionUseCase
import com.calypsan.listenup.client.domain.usecase.admin.UnstageCollectionUseCase
import com.calypsan.listenup.client.domain.usecase.admin.UpdateServerSettingsUseCase
import com.calypsan.listenup.client.domain.usecase.book.LoadBookForEditUseCase
import com.calypsan.listenup.client.domain.usecase.book.UpdateBookUseCase
import com.calypsan.listenup.client.domain.usecase.collection.AddBooksToCollectionUseCase
import com.calypsan.listenup.client.domain.usecase.collection.CreateCollectionUseCase
import com.calypsan.listenup.client.domain.usecase.collection.DeleteCollectionUseCase
import com.calypsan.listenup.client.domain.usecase.collection.GetUsersForSharingUseCase
import com.calypsan.listenup.client.domain.usecase.collection.LoadCollectionBooksUseCase
import com.calypsan.listenup.client.domain.usecase.collection.LoadCollectionSharesUseCase
import com.calypsan.listenup.client.domain.usecase.collection.RefreshCollectionsUseCase
import com.calypsan.listenup.client.domain.usecase.collection.RemoveBookFromCollectionUseCase
import com.calypsan.listenup.client.domain.usecase.collection.RemoveCollectionShareUseCase
import com.calypsan.listenup.client.domain.usecase.collection.ShareCollectionUseCase
import com.calypsan.listenup.client.domain.usecase.collection.UpdateCollectionNameUseCase
import com.calypsan.listenup.client.domain.usecase.contributor.ApplyContributorMetadataUseCase
import com.calypsan.listenup.client.domain.usecase.contributor.DeleteContributorUseCase
import com.calypsan.listenup.client.domain.usecase.contributor.UpdateContributorUseCase
import com.calypsan.listenup.client.domain.usecase.shelf.AddBooksToShelfUseCase
import com.calypsan.listenup.client.domain.usecase.shelf.CreateShelfUseCase
import com.calypsan.listenup.client.domain.usecase.shelf.DeleteShelfUseCase
import com.calypsan.listenup.client.domain.usecase.shelf.LoadShelfDetailUseCase
import com.calypsan.listenup.client.domain.usecase.shelf.RemoveBookFromShelfUseCase
import com.calypsan.listenup.client.domain.usecase.shelf.UpdateShelfUseCase
import com.calypsan.listenup.client.domain.usecase.library.RefreshLibraryUseCase
import com.calypsan.listenup.client.domain.usecase.metadata.ApplyMetadataMatchUseCase
import com.calypsan.listenup.client.domain.usecase.profile.LoadUserProfileUseCase
import com.calypsan.listenup.client.domain.usecase.series.UpdateSeriesUseCase
import org.koin.core.module.Module
import org.koin.core.module.dsl.factoryOf
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module
import com.calypsan.listenup.client.data.repository.ContributorEditRepository as ContributorEditRepositoryImpl
import com.calypsan.listenup.client.data.repository.SeriesEditRepository as SeriesEditRepositoryImpl

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
        // InviteApi - handles public invite operations (no auth required)
        // Server URL comes from deep link, not stored settings
        single { InviteApi() } bind InviteApiContract::class

        // InviteRepository - REST-backed; persists tokens and user on successful claim
        single<com.calypsan.listenup.client.domain.repository.InviteRepository> {
            com.calypsan.listenup.client.data.repository.InviteRepositoryImpl(
                inviteApi = get(),
                authSession = get(),
                userRepository = get(),
            )
        }

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
        }

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
        single<InstanceRepository> {
            val secureStorage: com.calypsan.listenup.core.SecureStorage = get()
            InstanceRepositoryImpl(
                getServerUrl = {
                    secureStorage.read("server_url")?.let { ServerUrl(it) }
                },
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
        single { get<ListenUpDatabase>().shelfDao() }
        single { get<ListenUpDatabase>().shelfBookDao() }
        single { get<ListenUpDatabase>().tagDao() }
        single { get<ListenUpDatabase>().genreDao() }
        single { get<ListenUpDatabase>().audioFileDao() }
        single { get<ListenUpDatabase>().listeningEventDao() }
        single { get<ListenUpDatabase>().activeSessionDao() }
        single { get<ListenUpDatabase>().activityDao() }
        single { get<ListenUpDatabase>().userStatsDao() }
        single { get<ListenUpDatabase>().tentativeSpanDao() }
        single { get<ListenUpDatabase>().userReadingSessionDao() }
        single { get<ListenUpDatabase>().readerSessionCacheDao() }
        single { get<ListenUpDatabase>().bookReadersSummaryDao() }

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
                                .named("appScope"),
                    ),
                urlChangeListener =
                    ServerUrlChangeListener { newUrl ->
                        // Update settings with new URL and invalidate every remote cache that
                        // captured the old HttpClient. When you add another such cache, drop
                        // an `invalidate()` call here too — there's no automatic propagation.
                        val serverConfig: ServerConfig = get()
                        val apiClientFactory: ApiClientFactory = get()
                        val authRpcFactory: AuthRpcFactory = get()
                        serverConfig.setServerUrl(newUrl)
                        apiClientFactory.invalidate()
                        authRpcFactory.invalidate()
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
                genreRepository = get(),
                tagRepository = get(),
                imageRepository = get(),
                imageStagingRepository = get(),
            )
        }
        // Metadata use cases
        factory {
            ApplyMetadataMatchUseCase(
                metadataRepository = get(),
                imageRepository = get(),
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
                imageRepository = get(),
                contributorRepository = get<com.calypsan.listenup.client.domain.repository.ContributorRepository>(),
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
        // Collection use cases
        factory {
            CreateCollectionUseCase(
                collectionRepository = get(),
            )
        }
        factory {
            DeleteCollectionUseCase(
                collectionRepository = get(),
            )
        }
        factory {
            AddBooksToCollectionUseCase(
                collectionRepository = get(),
            )
        }
        factory {
            RefreshCollectionsUseCase(
                collectionRepository = get(),
            )
        }
        factory {
            UpdateCollectionNameUseCase(
                collectionRepository = get(),
            )
        }
        factory {
            RemoveBookFromCollectionUseCase(
                collectionRepository = get(),
            )
        }
        factory {
            LoadCollectionBooksUseCase(
                collectionRepository = get(),
            )
        }
        factory {
            ShareCollectionUseCase(
                collectionRepository = get(),
            )
        }
        factory {
            RemoveCollectionShareUseCase(
                collectionRepository = get(),
            )
        }
        factory {
            LoadCollectionSharesUseCase(
                collectionRepository = get(),
                adminRepository = get(),
            )
        }
        factory {
            GetUsersForSharingUseCase(
                adminRepository = get(),
                collectionRepository = get(),
                userRepository = get(),
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
        factory {
            LoadInboxBooksUseCase(
                adminRepository = get(),
            )
        }
        factory {
            ReleaseBooksUseCase(
                adminRepository = get(),
            )
        }
        factory {
            StageCollectionUseCase(
                adminRepository = get(),
            )
        }
        factory {
            UnstageCollectionUseCase(
                adminRepository = get(),
            )
        }
        // Profile use cases
        factory {
            LoadUserProfileUseCase(
                profileRepository = get(),
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
                    .named("appScope"),
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

        // TagApi for user tag operations
        single {
            TagApi(clientFactory = get())
        } bind TagApiContract::class

        // GenreApi for genre operations
        single {
            GenreApi(clientFactory = get())
        } bind GenreApiContract::class

        // StatsApi for listening statistics
        single {
            StatsApi(clientFactory = get())
        } bind StatsApiContract::class

        // LeaderboardApi for social leaderboard
        single {
            LeaderboardApi(clientFactory = get())
        } bind LeaderboardApiContract::class

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

        // MetadataApi for Audible metadata search and matching
        single {
            MetadataApi(clientFactory = get())
        } bind MetadataApiContract::class

        // MetadataRepository for metadata operations (SOLID: interface in domain, impl in data)
        single<com.calypsan.listenup.client.domain.repository.MetadataRepository> {
            MetadataRepositoryImpl(metadataApi = get())
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
                                .named("appScope"),
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
                playbackApi = get(),
                playbackPreferences = get(),
                capabilityDetector = get(),
            )
        }

        // EventStreamRepository for real-time events (SOLID: interface in domain, impl in data)
        single<com.calypsan.listenup.client.domain.repository.EventStreamRepository> {
            EventStreamRepositoryImpl()
        }

        // AdminCollectionApi for admin collection operations
        single {
            AdminCollectionApi(clientFactory = get())
        } bind AdminCollectionApiContract::class

        // UserPreferencesApi for syncing user preferences across devices
        single {
            UserPreferencesApi(clientFactory = get())
        } bind UserPreferencesApiContract::class

        // ShelfApi for personal curation shelves
        single {
            ShelfApi(clientFactory = get())
        } bind ShelfApiContract::class

        // ProfileApi for user profile operations
        single {
            ProfileApi(clientFactory = get())
        } bind ProfileApiContract::class

        // SetupApi for library setup operations
        single {
            SetupApi(clientFactory = get())
        } bind SetupApiContract::class

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
                                .named("appScope"),
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
                                .named("appScope"),
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
        }

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

        // LeaderboardRepository for offline-first leaderboard (SOLID: interface in domain, impl in data)
        // Combines local listening events (current user) with activities (others)
        // Uses API only for initial All-time cache population
        single<com.calypsan.listenup.client.domain.repository.LeaderboardRepository> {
            LeaderboardRepositoryImpl(
                listeningEventDao = get(),
                activityDao = get(),
                userStatsDao = get(),
                userDao = get(),
                leaderboardApi = get(),
            )
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

        // BookEditRepository for book editing operations (offline-first, SOLID: domain interface)
        single<com.calypsan.listenup.client.domain.repository.BookEditRepository> {
            BookEditRepositoryImpl(
                bookDao = get(),
            )
        }

        // SeriesEditRepository for series editing operations (offline-first, SOLID: domain interface)
        single<SeriesEditRepository> {
            SeriesEditRepositoryImpl(
                seriesDao = get(),
            )
        }

        // ContributorEditRepository for contributor editing operations (offline-first, SOLID: domain interface)
        single<ContributorEditRepository> {
            ContributorEditRepositoryImpl(
                transactionRunner = get(),
                contributorDao = get(),
                contributorAliasDao = get(),
                bookContributorDao = get(),
            )
        }

        // ProfileEditRepository for profile editing operations (offline-first, SOLID: domain interface)
        single<com.calypsan.listenup.client.domain.repository.ProfileEditRepository> {
            ProfileEditRepositoryImpl(
                userDao = get(),
                profileApi = get(),
            )
        }

        // UserRepository for current user profile data (SOLID: interface in domain, impl in data)
        single<UserRepository> {
            UserRepositoryImpl(userDao = get(), sessionApi = get())
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
                scope =
                    get(
                        qualifier =
                            org.koin.core.qualifier
                                .named("appScope"),
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

        // TagRepository for community tags (SOLID: interface in domain, impl in data)
        single<TagRepository> {
            TagRepositoryImpl(dao = get(), tagApi = get())
        }

        // GenreRepository for hierarchical genres (SOLID: interface in domain, impl in data)
        single<GenreRepository> {
            GenreRepositoryImpl(dao = get(), genreApi = get())
        }

        // ShelfRepository for personal curation shelves (SOLID: interface in domain, impl in data)
        single<ShelfRepository> {
            ShelfRepositoryImpl(
                dao = get(),
                shelfBookDao = get(),
                userDao = get(),
                shelfApi = get(),
                transactionRunner = get(),
            )
        }

        // CollectionRepository for admin collections (SOLID: interface in domain, impl in data)
        single<CollectionRepository> {
            CollectionRepositoryImpl(dao = get(), adminCollectionApi = get())
        }

        // ActivityRepository for activity feed (SOLID: interface in domain, impl in data)
        single<ActivityRepository> {
            ActivityRepositoryImpl(dao = get(), activityFeedApi = get())
        }

        // ActiveSessionRepository for live sessions (SOLID: interface in domain, impl in data)
        single<ActiveSessionRepository> {
            ActiveSessionRepositoryImpl(dao = get(), imageStorage = get())
        }

        // AdminRepository for admin operations (SOLID: interface in domain, impl in data)
        single<AdminRepository> {
            AdminRepositoryImpl(adminApi = get())
        }

        // ProfileRepository for public user profiles (SOLID: interface in domain, impl in data)
        single<ProfileRepository> {
            ProfileRepositoryImpl(profileApi = get())
        }

        // UserPreferencesRepository for syncing user preferences across devices
        single<com.calypsan.listenup.client.domain.repository.UserPreferencesRepository> {
            com.calypsan.listenup.client.data.repository.UserPreferencesRepositoryImpl(
                userPreferencesApi = get(),
            )
        }

        // SessionRepository for reading sessions (SOLID: interface in domain, impl in data)
        // Offline-first: caches reader data in Room, syncs via API and SSE
        single<SessionRepository> {
            SessionRepositoryImpl(
                sessionApi = get(),
                userReadingSessionDao = get(),
                readerSessionCacheDao = get(),
                bookReadersSummaryDao = get(),
                transactionRunner = get(),
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
