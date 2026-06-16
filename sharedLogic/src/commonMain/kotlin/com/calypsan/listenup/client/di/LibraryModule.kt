package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.data.remote.KtorScannerRpcFactory
import com.calypsan.listenup.client.data.remote.ScannerRpcFactory
import com.calypsan.listenup.client.data.remote.KtorUserPreferencesRpcFactory
import com.calypsan.listenup.client.data.remote.SyncApi
import com.calypsan.listenup.client.data.remote.SyncApiContract
import com.calypsan.listenup.client.data.remote.UserPreferencesRpcFactory
import com.calypsan.listenup.client.data.repository.HomeRepositoryImpl
import com.calypsan.listenup.client.data.repository.LibraryRepositoryImpl
import com.calypsan.listenup.client.data.repository.PendingOperationRepositoryImpl
import com.calypsan.listenup.client.data.repository.SyncRepositoryImpl
import com.calypsan.listenup.client.data.repository.SyncStatusRepositoryImpl
import com.calypsan.listenup.client.data.repository.UserPreferencesRepositoryImpl
import com.calypsan.listenup.client.data.sync.LibraryResetHelper
import com.calypsan.listenup.client.data.sync.LibraryResetHelperContract
import com.calypsan.listenup.client.domain.repository.HomeRepository
import com.calypsan.listenup.client.domain.repository.LibraryRepository
import com.calypsan.listenup.client.domain.repository.PendingOperationRepository
import com.calypsan.listenup.client.domain.repository.SyncRepository
import com.calypsan.listenup.client.domain.repository.SyncStatusRepository
import com.calypsan.listenup.client.domain.repository.UserPreferencesRepository
import com.calypsan.listenup.client.domain.usecase.library.RefreshLibraryUseCase
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.binds
import org.koin.dsl.module

private const val APP_SCOPE = "appScope"

/**
 * Library aggregate Koin wiring — APIs, repositories, and use cases for the library
 * domain (sync, user preferences, library observation, pending operations, home screen).
 *
 * External dependencies (owned by other modules):
 *  - [com.calypsan.listenup.client.data.remote.ApiClientFactory] — `networkModule`
 *  - [com.calypsan.listenup.client.domain.repository.ServerConfig] — `settingsModule`
 *  - [com.calypsan.listenup.client.data.sync.SyncEngine] — `clientSyncRenovationModule`
 *  - [com.calypsan.listenup.client.data.sync.SyncEngineState] — `clientSyncRenovationModule`
 *  - [com.calypsan.listenup.client.domain.repository.AuthSession] — `clientAuthModule`
 *  - [com.calypsan.listenup.client.playback.ListeningEventRecorder] — platform playback modules
 *  - [com.calypsan.listenup.client.data.local.db.BookDao] — `persistenceModule`
 *  - [com.calypsan.listenup.client.data.local.db.PlaybackPositionDao] — `persistenceModule`
 *  - [com.calypsan.listenup.client.data.local.db.LibraryDao] — `persistenceModule`
 *  - [com.calypsan.listenup.client.data.local.db.LibraryFolderDao] — `persistenceModule`
 *  - [kotlinx.coroutines.CoroutineScope] (named `"appScope"`) — `appCoreModule`
 *  - [com.calypsan.listenup.client.domain.repository.BookRepository] — `bookModule`
 *  - [com.calypsan.listenup.client.data.local.db.ListenUpDatabase] — `platformDatabaseModule`
 *  - [com.calypsan.listenup.client.data.local.db.TransactionRunner] — `persistenceModule`
 *  - [com.calypsan.listenup.client.domain.repository.LibrarySync] — `settingsModule`
 */
val libraryModule: Module =
    module {
        // Sync API uses ApiClientFactory to get authenticated HttpClient at call time
        // This avoids runBlocking during DI initialization (structured concurrency)
        single<SyncApiContract> {
            SyncApi(clientFactory = get())
        }

        // UserPreferencesRpcFactory — kotlinx.rpc proxy for UserPreferencesService (#599).
        single<UserPreferencesRpcFactory> {
            KtorUserPreferencesRpcFactory(
                apiClientFactory = get(),
                serverConfig = get(),
            )
        } binds arrayOf(com.calypsan.listenup.client.data.remote.RemoteCache::class)

        single {
            LibraryResetHelper(
                database = get(),
                transactionRunner = get(),
                librarySyncContract = get(),
            )
        } bind LibraryResetHelperContract::class

        // ScannerRpcFactory — kotlinx.rpc proxy for ScannerService (public mount):
        // live scan-progress stream that drives the scan UI + post-scan reconcile.
        single<ScannerRpcFactory> {
            KtorScannerRpcFactory(
                apiClientFactory = get(),
                serverConfig = get(),
            )
        } binds arrayOf(com.calypsan.listenup.client.data.remote.RemoteCache::class)

        // HomeRepository for Home screen data (local-first)
        single<HomeRepository> {
            HomeRepositoryImpl(
                bookRepository = get(),
                playbackPositionDao = get(),
            )
        }

        // SyncRepository for library sync operations (SOLID: interface in domain, impl in data)
        single<SyncRepository> {
            SyncRepositoryImpl(
                syncEngine = get(),
                syncEngineState = get(),
                authSession = get(),
                listeningEventRecorder = get(),
                scannerRpcFactory = get(),
                bookDao = get(),
                listeningEventDao = get(),
                ftsPopulator = get(),
                scope =
                    get(
                        qualifier =
                            named(APP_SCOPE),
                    ),
            )
        }

        // UserPreferencesRepository for syncing user preferences across devices
        single<UserPreferencesRepository> {
            UserPreferencesRepositoryImpl(
                rpcFactory = get(),
            )
        }

        // SyncStatusRepository for sync timestamp tracking (SOLID: interface in domain, impl in data)
        single<SyncStatusRepository> {
            SyncStatusRepositoryImpl()
        }

        // PendingOperationRepository (domain) for UI observation of sync status
        // Wraps the data layer contract to provide domain models to ViewModels
        single<PendingOperationRepository> {
            PendingOperationRepositoryImpl()
        }

        // LibraryRepository — observation-only Room-backed view of the libraries domain.
        // Hygiene fix: uses get() for the two DAO bindings that persistenceModule already owns,
        // rather than calling db.libraryDao()/db.libraryFolderDao() inline.
        single<LibraryRepository> {
            LibraryRepositoryImpl(
                libraryDao = get(),
                libraryFolderDao = get(),
            )
        }

        // Library use cases (using domain layer interfaces only)
        factory {
            RefreshLibraryUseCase(
                syncRepository = get(),
            )
        }
    }
