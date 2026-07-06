package com.calypsan.listenup.client.di

import com.calypsan.listenup.api.dto.auth.DeviceInfo
import com.calypsan.listenup.core.IODispatcher
import com.calypsan.listenup.client.core.appCoroutineExceptionHandler
import com.calypsan.listenup.client.device.DeviceInfoProvider
import com.calypsan.listenup.client.download.DownloadFileManager
import com.calypsan.listenup.client.download.DownloadService
import com.calypsan.listenup.client.download.AppleDownloadService
import com.calypsan.listenup.client.playback.ApplePlaybackController
import com.calypsan.listenup.client.playback.AudioTokenProvider
import com.calypsan.listenup.client.playback.CachedAudioTokenProvider
import com.calypsan.listenup.client.playback.AudioPlayer
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.client.data.sync.SyncDomainHandler
import com.calypsan.listenup.client.playback.AvFoundationAudioPlayer
import com.calypsan.listenup.client.playback.PlaybackController
import com.calypsan.listenup.client.playback.PlaybackManager
import com.calypsan.listenup.client.playback.PlaybackManagerImpl
import com.calypsan.listenup.client.playback.PlaybackProgressReporter
import com.calypsan.listenup.client.playback.ProgressTracker
import com.calypsan.listenup.client.playback.SleepTimerManager
import com.calypsan.listenup.client.sync.BackgroundSyncScheduler
import com.calypsan.listenup.client.sync.MacosBackgroundSyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import platform.Foundation.NSProcessInfo
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

private const val PLAYBACK_SCOPE = "playbackScope"

/**
 * macOS playback module.
 *
 * Provides audio playback components for macOS:
 * - AudioTokenProvider for authenticated streaming
 * - DownloadService for offline downloads
 * - PlaybackManager for playback orchestration
 * - ProgressTracker for position persistence
 * - SleepTimerManager for sleep timer functionality
 */
internal val macosPlaybackModule: Module =
    module {
        // Playback-scoped coroutine scope. The appCoroutineExceptionHandler keeps an uncaught
        // failure in a fire-and-forget playback launch from terminating the process on Kotlin/Native
        // (macOS is Kotlin/Native too) — it logs loudly instead.
        single(qualifier = named(PLAYBACK_SCOPE)) {
            CoroutineScope(SupervisorJob() + IODispatcher + appCoroutineExceptionHandler)
        }

        // Device ID for listening events
        // macOS derives the id from the host name
        // TODO: Use a more stable identifier when macOS app matures
        single(qualifier = named("deviceId")) {
            val hostName = NSProcessInfo.processInfo.hostName
            "macos-$hostName"
        }

        // File manager for downloads
        single { DownloadFileManager() }

        // Audio token provider — shared core; no platform-specific surface needed
        single<AudioTokenProvider> {
            CachedAudioTokenProvider(
                authSession = get(),
                authRepository = get(),
                scope = get(qualifier = named(PLAYBACK_SCOPE)),
            )
        }

        // Download service
        single<DownloadService> {
            AppleDownloadService(
                downloadDao = get(),
                bookDao = get(),
                audioFileDao = get(),
                serverConfig = get(),
                tokenProvider = get(),
                fileManager = get(),
                playbackRpcFactory = get(),
                scope = get(qualifier = named(PLAYBACK_SCOPE)),
            )
        }

        // Progress tracker
        single {
            ProgressTracker(
                downloadRepository = get(),
                positionRepository = get(),
                scope = get(qualifier = named(PLAYBACK_SCOPE)),
            )
        }

        // Position reporter for the PlaybackManagerImpl seam. macOS has no Media3
        // `PlaybackService`, so the reporter is the only driver of listening-event
        // recording here — it is bound WITH the recorder.
        single {
            PlaybackProgressReporter(
                progressTracker = get(),
                recorder = get(),
                scope = get(qualifier = named(PLAYBACK_SCOPE)),
            )
        }

        // Structured device identity — shared source for auth login + listening history.
        single<DeviceInfoProvider> {
            DeviceInfoProvider {
                DeviceInfo(
                    deviceType = "desktop",
                    platform = "macOS",
                    clientName = "ListenUp Desktop",
                    deviceName = NSProcessInfo.processInfo.hostName,
                )
            }
        }

        // Sleep timer manager
        single {
            SleepTimerManager(
                scope = get(qualifier = named(PLAYBACK_SCOPE)),
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
                reporter = get(),
                tokenProvider = get(),
                downloadService = get(),
                playbackRpcFactory = get(),
                bookRpcFactory = get(),
                deviceContext = get(),
                scope = get(qualifier = named(PLAYBACK_SCOPE)),
                bookSyncDomainHandler = get<SyncDomainHandler<BookSyncPayload>>(named(SyncDomains.BOOKS.name)),
            )
        }

        // Audio player
        single<AudioPlayer> {
            AvFoundationAudioPlayer(
                tokenProvider = get(),
                scope = get(qualifier = named(PLAYBACK_SCOPE)),
            )
        }

        // Playback controller seam (delegates to AudioPlayer for command-side operations)
        single<PlaybackController> {
            ApplePlaybackController(
                audioPlayer = get(),
                playbackManager = get(),
            )
        }

        // Background sync scheduler (stub)
        single<BackgroundSyncScheduler> { MacosBackgroundSyncScheduler() }
    }
