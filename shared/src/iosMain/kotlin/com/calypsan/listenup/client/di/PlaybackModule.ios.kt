@file:Suppress("StringLiteralDuplication")

package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.core.IODispatcher
import com.calypsan.listenup.client.download.AppleDownloadEnqueuer
import com.calypsan.listenup.client.download.DownloadEnqueuer
import com.calypsan.listenup.client.download.DownloadFileManager
import com.calypsan.listenup.client.download.DownloadService
import com.calypsan.listenup.client.download.AppleDownloadService
import com.calypsan.listenup.client.playback.ApplePlaybackController
import com.calypsan.listenup.client.playback.AudioTokenProvider
import com.calypsan.listenup.client.playback.CachedAudioTokenProvider
import com.calypsan.listenup.client.playback.AudioPlayer
import com.calypsan.listenup.client.playback.AvFoundationAudioPlayer
import com.calypsan.listenup.client.playback.PlaybackController
import com.calypsan.listenup.client.playback.PlaybackManager
import com.calypsan.listenup.client.playback.PlaybackManagerImpl
import com.calypsan.listenup.client.playback.PlaybackPreparer
import com.calypsan.listenup.client.playback.ProgressTracker
import com.calypsan.listenup.client.playback.SleepTimerManager
import com.calypsan.listenup.client.sync.BackgroundSyncScheduler
import com.calypsan.listenup.client.sync.IosBackgroundSyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * iOS playback module.
 *
 * Provides audio playback components for iOS:
 * - AudioTokenProvider for authenticated streaming
 * - DownloadService for offline downloads
 * - PlaybackManager for playback orchestration
 * - ProgressTracker for position persistence
 * - SleepTimerManager for sleep timer functionality
 */
val iosPlaybackModule: Module =
    module {
        // Playback-scoped coroutine scope
        single(qualifier = named("playbackScope")) {
            CoroutineScope(SupervisorJob() + IODispatcher)
        }

        // Device ID for listening events
        // iOS uses identifierForVendor which persists across app reinstalls
        // but changes when all apps from vendor are deleted
        single(qualifier = named("deviceId")) {
            platform.UIKit.UIDevice.currentDevice.identifierForVendor
                ?.UUIDString ?: "unknown-device"
        }

        // File manager for downloads
        single { DownloadFileManager() }

        // Audio token provider — shared core; no iOS-specific surface needed
        single<AudioTokenProvider> {
            CachedAudioTokenProvider(
                authSession = get(),
                authRepository = get(),
                scope = get(qualifier = named("playbackScope")),
            )
        }

        // DownloadEnqueuer seam — iOS no-op (NSURLSession path is W10 carveout)
        single<DownloadEnqueuer> { AppleDownloadEnqueuer() }

        // Download service
        single<DownloadService> {
            AppleDownloadService(
                downloadDao = get(),
                bookDao = get(),
                audioFileDao = get(),
                serverConfig = get(),
                tokenProvider = get(),
                fileManager = get(),
                scope = get(qualifier = named("playbackScope")),
            )
        }

        // Progress tracker
        single {
            ProgressTracker(
                downloadRepository = get(),
                listeningEventRepository = get(),
                syncApi = get(),
                positionRepository = get(),
                scope = get(qualifier = named("playbackScope")),
            )
        }

        // Stateless playback-preparation pipeline — consumed directly by the native iOS player
        single {
            PlaybackPreparer(
                serverConfig = get(),
                playbackPreferences = get(),
                bookDao = get(),
                audioFileDao = get(),
                chapterDao = get(),
                imageStorage = get(),
                progressTracker = get(),
                tokenProvider = get(),
                deviceContext = get(),
                downloadService = get(),
                playbackApi = null,
                capabilityDetector = null,
                syncApi = get(),
                scope = get(qualifier = named("playbackScope")),
                bookRepository = get(),
            )
        }

        // Sleep timer manager
        single {
            SleepTimerManager(
                scope = get(qualifier = named("playbackScope")),
            )
        }

        // Playback manager
        single<PlaybackManager> {
            PlaybackManagerImpl(
                serverConfig = get(),
                playbackPreferences = get(),
                bookDao = get(),
                audioFileDao = get(),
                chapterDao = get(),
                imageStorage = get(),
                progressTracker = get(),
                tokenProvider = get(),
                downloadService = get(),
                playbackApi = null, // iOS uses native AVPlayer, no transcoding API needed
                capabilityDetector = null, // iOS doesn't need codec detection
                syncApi = get(),
                deviceContext = get(),
                scope = get(qualifier = named("playbackScope")),
                bookRepository = get(),
            )
        }

        // Audio player
        single<AudioPlayer> {
            AvFoundationAudioPlayer(
                tokenProvider = get(),
                scope = get(qualifier = named("playbackScope")),
            )
        }

        // Playback controller seam (delegates to AudioPlayer for command-side operations)
        single<PlaybackController> {
            ApplePlaybackController(
                audioPlayer = get(),
                playbackManager = get(),
            )
        }

        // Background sync scheduler
        single<BackgroundSyncScheduler> { IosBackgroundSyncScheduler() }
    }
