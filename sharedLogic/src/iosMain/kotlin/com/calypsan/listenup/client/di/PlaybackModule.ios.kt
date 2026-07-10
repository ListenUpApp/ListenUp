package com.calypsan.listenup.client.di

import com.calypsan.listenup.api.dto.auth.DeviceInfo
import com.calypsan.listenup.core.IODispatcher
import com.calypsan.listenup.client.core.appCoroutineExceptionHandler
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.client.data.sync.SyncDomainHandler
import com.calypsan.listenup.client.device.DeviceInfoProvider
import com.calypsan.listenup.client.download.AppleDownloadEnqueuer
import com.calypsan.listenup.client.download.DownloadEnqueuer
import com.calypsan.listenup.client.download.DownloadFileManager
import com.calypsan.listenup.client.download.DownloadService
import com.calypsan.listenup.client.download.AppleDownloadService
import com.calypsan.listenup.client.playback.AudioTokenProvider
import com.calypsan.listenup.client.playback.CachedAudioTokenProvider
import com.calypsan.listenup.client.playback.PlaybackPreparer
import com.calypsan.listenup.client.playback.PlaybackProgressReporter
import com.calypsan.listenup.client.playback.ProgressTracker
import com.calypsan.listenup.client.playback.SleepTimerManager
import com.calypsan.listenup.client.sync.BackgroundSyncScheduler
import com.calypsan.listenup.client.sync.IosBackgroundSyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

private const val PLAYBACK_SCOPE = "playbackScope"

/**
 * iOS playback module.
 *
 * The native Swift `PlayerCoordinator` owns playback orchestration; this module
 * provides only the shared-domain seam it consumes:
 * - AudioTokenProvider for authenticated streaming
 * - DownloadService for offline downloads
 * - PlaybackPreparer — stateless playback-preparation pipeline
 * - ProgressTracker — position persistence + server sync
 * - SleepTimerManager — sleep-timer state
 */
internal val iosPlaybackModule: Module =
    module {
        // Platform capability flag: iOS supports download and playback.
        single(qualifier = named("playbackAvailable")) { true }

        // Playback-scoped coroutine scope. The appCoroutineExceptionHandler keeps an uncaught
        // failure in a fire-and-forget playback launch (e.g. a best-effort background download
        // throwing) from terminating the process on Kotlin/Native — it logs loudly instead.
        single(qualifier = named(PLAYBACK_SCOPE)) {
            CoroutineScope(SupervisorJob() + IODispatcher + appCoroutineExceptionHandler)
        }

        // Device ID for listening events
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
                scope = get(qualifier = named(PLAYBACK_SCOPE)),
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
                playbackRpcFactory = get(),
                scope = get(qualifier = named(PLAYBACK_SCOPE)),
                playbackBandwidthCoordinator = get(),
            )
        }

        // Progress tracker — position persistence, wrapped by PlaybackProgressReporter below.
        single {
            ProgressTracker(
                downloadRepository = get(),
                positionRepository = get(),
                scope = get(qualifier = named(PLAYBACK_SCOPE)),
            )
        }

        // Position reporter — the single playback-session seam consumed by the native Swift
        // PlayerCoordinator (via KotlinProgressReporting). Bound WITH the recorder so iOS
        // listening history is recorded with the account user id and synced to the server.
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
                    deviceType = "phone",
                    platform = "iOS",
                    platformVersion = platform.UIKit.UIDevice.currentDevice.systemVersion,
                    clientName = "ListenUp iOS",
                    deviceName = platform.UIKit.UIDevice.currentDevice.name,
                )
            }
        }

        // Stateless playback-preparation pipeline — consumed directly by PlayerCoordinator
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
                playbackRpcFactory = get(),
                bookRpcFactory = get(),
                scope = get(qualifier = named(PLAYBACK_SCOPE)),
                bookSyncDomainHandler = get<SyncDomainHandler<BookSyncPayload>>(named(SyncDomains.BOOKS.name)),
            )
        }

        // Sleep timer manager — observed via SKIE by PlayerCoordinator
        single {
            SleepTimerManager(
                scope = get(qualifier = named(PLAYBACK_SCOPE)),
            )
        }

        // Background sync scheduler
        single<BackgroundSyncScheduler> { IosBackgroundSyncScheduler() }
    }
