package com.calypsan.listenup.client.di

import androidx.work.WorkManager
import com.calypsan.listenup.client.data.local.db.AudioFileDao
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.ChapterDao
import com.calypsan.listenup.client.data.local.db.DownloadDao
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.campfire.ActiveCampfireCoordinator
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.sync.SyncDomainHandler
import com.calypsan.listenup.client.device.DeviceContext
import com.calypsan.listenup.client.device.DeviceType
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.DocumentRepository
import com.calypsan.listenup.client.domain.repository.DownloadRepository
import com.calypsan.listenup.client.domain.repository.HomeRepository
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.LocalPreferences
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import com.calypsan.listenup.client.domain.repository.PlaybackPositionRepository
import com.calypsan.listenup.client.domain.repository.PlaybackPreferences
import com.calypsan.listenup.client.domain.repository.PlaybackPrepareRepository
import com.calypsan.listenup.client.domain.repository.SearchRepository
import com.calypsan.listenup.client.domain.repository.SeriesRepository
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.download.DownloadService
import com.calypsan.listenup.client.playback.AudioTokenProvider
import com.calypsan.listenup.client.playback.ListeningEventRecorder
import com.calypsan.listenup.client.playback.PlaybackController
import com.calypsan.listenup.client.playback.PlaybackManager
import com.calypsan.listenup.client.playback.ProgressTracker
import com.calypsan.listenup.client.playback.SleepTimerManager
import io.kotest.core.spec.style.FunSpec
import kotlinx.coroutines.CoroutineScope
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.test.verify.verify

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
class KoinModuleVerifyTest :
    FunSpec({
        // Verify voiceModule — the voice intent resolver and its four repository dependencies.
        //
        // Narrow module; the extraTypes list is correspondingly small. Part of the
        // "every leaf module is verified" expansion. PresentationModule
        // verification is deferred until the DI layout is rewritten, which
        // would immediately invalidate any extraTypes enumerated today.
        test("verifyVoiceModule") {
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

        // Verify [playbackPresentationModule] — the single shared playback VM is bound as `single`.
        // This module was previously not covered by module.verify().
        //
        // `extraTypes` enumerates cross-module dependencies that other Koin modules satisfy.
        test("verifyPlaybackPresentationModule") {
            playbackPresentationModule.verify(
                extraTypes =
                    listOf(
                        PlaybackManager::class,
                        PlaybackController::class,
                        BookRepository::class,
                        SleepTimerManager::class,
                        PlaybackPreferences::class,
                        NetworkMonitor::class,
                        DocumentRepository::class,
                        DownloadRepository::class,
                        PlaybackPositionRepository::class,
                        ActiveCampfireCoordinator::class,
                    ),
            )
        }

        // Verify [androidPlaybackModule] — wires PlaybackProgressReporter and the Android
        // PlaybackManagerImpl. extraTypes covers all cross-module get() targets: DAOs, repos,
        // config, and infrastructure types provided by other modules at runtime.
        test("verifyAndroidPlaybackModule") {
            androidPlaybackModule.verify(
                extraTypes =
                    listOf(
                        ProgressTracker::class,
                        ListeningEventRecorder::class,
                        CoroutineScope::class,
                        ServerConfig::class,
                        PlaybackPreferences::class,
                        BookDao::class,
                        AudioFileDao::class,
                        ChapterDao::class,
                        ImageStorage::class,
                        AudioTokenProvider::class,
                        DeviceContext::class,
                        DownloadService::class,
                        PlaybackPrepareRepository::class,
                        RpcChannel::class,
                        SyncDomainHandler::class,
                    ),
            )
        }

        // Verify [androidDownloadModule] — wires DownloadFileManager, DownloadManager (bound
        // to DownloadService), and AndroidDownloadEnqueuer (bound to DownloadEnqueuer).
        // WorkManager is constructed inline via WorkManager.getInstance(get<Context>()) — it is
        // not a get() target itself, but Context is.
        test("verifyAndroidDownloadModule") {
            androidDownloadModule.verify(
                extraTypes =
                    listOf(
                        android.content.Context::class,
                        WorkManager::class,
                        DownloadDao::class,
                        BookDao::class,
                        AudioFileDao::class,
                        LocalPreferences::class,
                        DownloadRepository::class,
                        TransactionRunner::class,
                    ),
            )
        }

        // Verify [platformDiscoveryModule] — NsdDiscoveryService bound to ServerDiscoveryService.
        // Only cross-module dependency is Context (provided by androidContext() at startup).
        test("verifyPlatformDiscoveryModule") {
            platformDiscoveryModule.verify(
                extraTypes = listOf(android.content.Context::class),
            )
        }

        // Verify [platformDeviceModule] — DeviceContextProvider (needs Context) and the
        // DeviceContext produced by DeviceContextProvider.detect(). The second single resolves
        // DeviceContextProvider from within the same module, so no additional extraTypes needed.
        test("verifyPlatformDeviceModule") {
            platformDeviceModule.verify(
                extraTypes =
                    listOf(
                        android.content.Context::class,
                        DeviceType::class,
                    ),
            )
        }
    })
