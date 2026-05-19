package com.calypsan.listenup.client.di

import com.calypsan.listenup.core.SecureStorage
import com.calypsan.listenup.client.data.discovery.ServerDiscoveryService
import com.calypsan.listenup.client.data.local.db.ActiveSessionDao
import com.calypsan.listenup.client.data.local.db.ActivityDao
import com.calypsan.listenup.client.data.local.db.BookContributorDao
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.BookSeriesDao
import com.calypsan.listenup.client.data.local.db.ChapterDao
import com.calypsan.listenup.client.data.local.db.CollectionDao
import com.calypsan.listenup.client.data.local.db.ContributorDao
import com.calypsan.listenup.client.data.local.db.DownloadDao
import com.calypsan.listenup.client.data.local.db.ShelfDao
import com.calypsan.listenup.client.data.local.db.ListeningEventDao
import com.calypsan.listenup.client.data.local.db.PlaybackPositionDao
import com.calypsan.listenup.client.data.local.db.SearchDao
import com.calypsan.listenup.client.data.local.db.SeriesDao
import com.calypsan.listenup.client.data.local.db.ServerDao
import com.calypsan.listenup.client.data.local.db.AudioFileDao
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.BookReadersSummaryDao
import com.calypsan.listenup.client.data.local.db.GenreDao
import com.calypsan.listenup.client.data.local.db.ReaderSessionCacheDao
import com.calypsan.listenup.client.data.local.db.ShelfBookDao
import com.calypsan.listenup.client.data.local.db.TagDao
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.local.db.UserDao
import com.calypsan.listenup.client.data.local.db.UserReadingSessionDao
import com.calypsan.listenup.client.data.local.db.UserProfileDao
import com.calypsan.listenup.client.data.local.db.UserStatsDao
import com.calypsan.listenup.client.data.local.images.CoverColorExtractor
import com.calypsan.listenup.client.data.remote.ApiClientFactory
import com.calypsan.listenup.client.data.remote.BookApiContract
import com.calypsan.listenup.client.data.remote.ContributorApiContract
import com.calypsan.listenup.client.data.remote.GenreApiContract
import com.calypsan.listenup.client.data.remote.ImageApiContract
import com.calypsan.listenup.client.data.remote.InstanceApiContract
import com.calypsan.listenup.client.data.remote.SearchApiContract
import com.calypsan.listenup.client.data.remote.SeriesApiContract
import com.calypsan.listenup.client.data.remote.SyncApiContract
import com.calypsan.listenup.client.data.remote.TagApiContract
import com.calypsan.listenup.client.data.remote.UserPreferencesApiContract
import com.calypsan.listenup.client.playback.PlaybackStateProvider
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.HomeRepository
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import com.calypsan.listenup.client.domain.repository.LibraryPreferences
import com.calypsan.listenup.client.domain.repository.LibrarySync
import com.calypsan.listenup.client.domain.repository.LocalPreferences
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import com.calypsan.listenup.client.domain.repository.SearchRepository
import com.calypsan.listenup.client.domain.repository.SeriesRepository
import com.calypsan.listenup.client.domain.repository.PlaybackPreferences
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.download.DownloadService
import com.calypsan.listenup.client.playback.PlaybackController
import com.calypsan.listenup.client.playback.PlaybackManager
import com.calypsan.listenup.client.playback.SleepTimerManager
import kotlinx.coroutines.CoroutineScope
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.test.verify.verify
import kotlin.test.Test

/**
 * Verifies Koin module definitions are correctly configured.
 *
 * This test uses Koin's verify() API to statically check that all constructor
 * dependencies have corresponding definitions. This catches issues like:
 * - Missing interface bindings (e.g., concrete class registered but interface not bound)
 * - Missing dependency definitions
 * - Circular dependencies
 *
 * Note: Platform-specific dependencies (DAOs, APIs, etc.) are declared as extraTypes
 * since they're defined in platform modules that can't be loaded in commonTest.
 */
@OptIn(KoinExperimentalAPI::class)
class KoinModuleVerifyTest {
    /**
     * Verify syncModule definitions.
     */
    @Test
    fun verifySyncModule() {
        syncModule.verify(
            extraTypes =
                listOf(
                    // Platform-specific types provided by other modules
                    CoroutineScope::class,
                    ListenUpDatabase::class,
                    TransactionRunner::class,
                    SecureStorage::class,
                    ImageStorage::class,
                    CoverColorExtractor::class,
                    NetworkMonitor::class,
                    // DAOs from database module
                    ActiveSessionDao::class,
                    ActivityDao::class,
                    AudioFileDao::class,
                    BookContributorDao::class,
                    BookDao::class,
                    BookReadersSummaryDao::class,
                    BookSeriesDao::class,
                    ChapterDao::class,
                    CollectionDao::class,
                    ContributorDao::class,
                    com.calypsan.listenup.client.data.local.db.CoverDownloadDao::class,
                    DownloadDao::class,
                    GenreDao::class,
                    ListeningEventDao::class,
                    PlaybackPositionDao::class,
                    ReaderSessionCacheDao::class,
                    SearchDao::class,
                    SeriesDao::class,
                    ServerDao::class,
                    ShelfBookDao::class,
                    ShelfDao::class,
                    TagDao::class,
                    UserDao::class,
                    UserProfileDao::class,
                    UserReadingSessionDao::class,
                    UserStatsDao::class,
                    // Playback and download services
                    PlaybackManager::class,
                    PlaybackStateProvider::class,
                    DownloadService::class,
                    // Settings interfaces (ISP-compliant segregated interfaces)
                    AuthSession::class,
                    LibrarySync::class,
                    ServerConfig::class,
                    LibraryPreferences::class,
                    PlaybackPreferences::class,
                    LocalPreferences::class,
                    // Repositories and APIs from other modules
                    InstanceRepository::class,
                    ApiClientFactory::class,
                    InstanceApiContract::class,
                    BookApiContract::class,
                    ContributorApiContract::class,
                    SeriesApiContract::class,
                    SyncApiContract::class,
                    SearchApiContract::class,
                    ImageApiContract::class,
                    GenreApiContract::class,
                    TagApiContract::class,
                    UserPreferencesApiContract::class,
                    ServerDiscoveryService::class,
                ),
        )
    }

    /**
     * Verify voiceModule — the voice intent resolver and its four repository dependencies.
     *
     * Narrow module; the extraTypes list is correspondingly small. Covered as step 1 of
     * the Finding 12 D4 expansion ("every leaf module is verified"). PresentationModule
     * verification is deferred to W3, which rewrites the DI layout (Finding 02 R1) and
     * would immediately invalidate any extraTypes enumerated today.
     */
    @Test
    fun verifyVoiceModule() {
        voiceModule.verify(
            extraTypes =
                listOf(
                    SearchRepository::class,
                    HomeRepository::class,
                    SeriesRepository::class,
                    BookRepository::class,
                ),
        )
    }

    /**
     * Verify [playbackPresentationModule] — single shared playback VM bound as `single`
     * per W7 Phase E2.2.3 consolidation. Closes drift #33 ("playbackPresentationModule
     * not covered by module.verify()").
     *
     * `extraTypes` enumerates cross-module dependencies that other Koin modules satisfy.
     */
    @Test
    fun verifyPlaybackPresentationModule() {
        playbackPresentationModule.verify(
            extraTypes =
                listOf(
                    PlaybackManager::class,
                    PlaybackController::class,
                    BookRepository::class,
                    SleepTimerManager::class,
                    PlaybackPreferences::class,
                    NetworkMonitor::class,
                ),
        )
    }
}
