package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.data.local.db.ActivityDao
import com.calypsan.listenup.client.data.local.db.AudioFileDao
import com.calypsan.listenup.client.data.local.db.BookContributorDao
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.BookSeriesDao
import com.calypsan.listenup.client.data.local.db.ChapterDao
import com.calypsan.listenup.client.data.local.db.CollectionDao
import com.calypsan.listenup.client.data.local.db.ContributorDao
import com.calypsan.listenup.client.data.local.db.CoverDownloadDao
import com.calypsan.listenup.client.data.local.db.DownloadDao
import com.calypsan.listenup.client.data.local.db.GenreDao
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.ListeningEventDao
import com.calypsan.listenup.client.data.local.db.PlaybackPositionDao
import com.calypsan.listenup.client.data.local.db.SearchDao
import com.calypsan.listenup.client.data.local.db.SeriesDao
import com.calypsan.listenup.client.data.local.db.ServerDao
import com.calypsan.listenup.client.data.local.db.ShelfBookDao
import com.calypsan.listenup.client.data.local.db.ShelfDao
import com.calypsan.listenup.client.data.local.db.TagDao
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.local.db.UserDao
import com.calypsan.listenup.client.data.local.db.UserProfileDao
import com.calypsan.listenup.client.data.local.db.UserStatsDao
import com.calypsan.listenup.client.data.remote.ApiClientFactory
import com.calypsan.listenup.client.data.remote.BookApiContract
import com.calypsan.listenup.client.data.remote.ContributorApiContract
import com.calypsan.listenup.client.data.remote.SeriesApiContract
import com.calypsan.listenup.client.data.sync.PendingOperationQueue
import com.calypsan.listenup.client.data.sync.SyncEngine
import com.calypsan.listenup.client.data.sync.SyncEngineState
import com.calypsan.listenup.client.data.sync.handlers.BookSyncDomainHandler
import com.calypsan.listenup.client.data.sync.handlers.ContributorSyncDomainHandler
import com.calypsan.listenup.client.data.sync.handlers.SeriesSyncDomainHandler
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.LibrarySync
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.download.DownloadEnqueuer
import com.calypsan.listenup.client.playback.ListeningEventRecorder
import com.calypsan.listenup.core.error.ErrorBus
import io.kotest.core.spec.style.FunSpec
import kotlinx.coroutines.CoroutineScope
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.test.verify.verify

/**
 * Leaf verify for [syncModule]. Per the architecture rubric every leaf
 * Koin module is covered by a `module.verify()` test in commonTest.
 *
 * The whitelist enumerates dependencies the module's bindings pull in but
 * other modules own:
 *
 *  - DAOs — owned by `repositoryModule` (exposed via `ListenUpDatabase` factory calls).
 *  - [ListenUpDatabase] — owned by `platformDatabaseModule`.
 *  - [TransactionRunner] — owned by `repositoryModule`.
 *  - [ApiClientFactory] — owned by `networkModule` (bearer-equipped HttpClient factory).
 *  - [ServerConfig] — owned by `dataModule` (segregated interface → `SettingsRepositoryImpl`).
 *  - [LibrarySync] — owned by `dataModule` (segregated interface → `SettingsRepositoryImpl`).
 *  - [ImageStorage] — owned by the platform storage module.
 *  - [NetworkMonitor] — owned by the platform device module.
 *  - [AuthSession] — owned by `clientAuthModule`.
 *  - [DownloadEnqueuer] — owned by the platform download module.
 *  - [BookApiContract], [ContributorApiContract], [SeriesApiContract] — owned by `networkModule`.
 *  - [SyncEngine], [SyncEngineState], [PendingOperationQueue] — owned by
 *    `clientSyncRenovationModule`.
 *  - [BookSyncDomainHandler], [ContributorSyncDomainHandler], [SeriesSyncDomainHandler]
 *    — owned by `clientSyncRenovationModule`.
 *  - [ListeningEventRecorder] — owned by the platform playback module.
 *  - [ErrorBus] — owned by `dataModule`.
 *  - [CoroutineScope] named `appScope` — produced by this module itself; the unqualified
 *    class is listed so Koin's verify can find it for the named qualifier resolution paths.
 *  - [String] named `deviceId` — owned by `dataModule` (device-identity qualifier).
 *
 * B2b additions covered implicitly: `MetadataLookupRpcFactory` (produced by this module;
 * its deps `ApiClientFactory` + `ServerConfig` are in the whitelist above) and
 * `MetadataRepository` (produced by this module; its dep `MetadataLookupRpcFactory` is
 * self-provided).
 */
@OptIn(KoinExperimentalAPI::class)
class SyncModuleVerifyTest :
    FunSpec({

        test("syncModule wires up against its declared external dependencies") {
            syncModule.verify(
                extraTypes =
                    listOf(
                        // Platform / database types
                        ListenUpDatabase::class,
                        TransactionRunner::class,
                        ImageStorage::class,
                        NetworkMonitor::class,
                        // DAOs (provided by repositoryModule via ListenUpDatabase)
                        ActivityDao::class,
                        AudioFileDao::class,
                        BookContributorDao::class,
                        BookDao::class,
                        BookSeriesDao::class,
                        ChapterDao::class,
                        CollectionDao::class,
                        ContributorDao::class,
                        CoverDownloadDao::class,
                        DownloadDao::class,
                        GenreDao::class,
                        ListeningEventDao::class,
                        PlaybackPositionDao::class,
                        SearchDao::class,
                        SeriesDao::class,
                        ServerDao::class,
                        ShelfBookDao::class,
                        ShelfDao::class,
                        TagDao::class,
                        UserDao::class,
                        UserProfileDao::class,
                        UserStatsDao::class,
                        // Network / API layer (networkModule)
                        ApiClientFactory::class,
                        BookApiContract::class,
                        ContributorApiContract::class,
                        SeriesApiContract::class,
                        // Settings / data layer (dataModule)
                        ServerConfig::class,
                        LibrarySync::class,
                        ErrorBus::class,
                        // Auth (clientAuthModule)
                        AuthSession::class,
                        // Sync engine (clientSyncRenovationModule)
                        SyncEngine::class,
                        SyncEngineState::class,
                        PendingOperationQueue::class,
                        BookSyncDomainHandler::class,
                        ContributorSyncDomainHandler::class,
                        SeriesSyncDomainHandler::class,
                        // Playback + download (platform modules)
                        DownloadEnqueuer::class,
                        ListeningEventRecorder::class,
                        // Primitive qualifiers
                        String::class,
                        // Coroutine scope — named "appScope" is self-produced; unqualified class
                        // must appear here for verify to accept the named qualifier resolution.
                        CoroutineScope::class,
                    ),
            )
        }
    })
