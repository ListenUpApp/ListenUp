@file:Suppress("StringLiteralDuplication")

package com.calypsan.listenup.client.di

import com.calypsan.listenup.api.dto.auth.DeviceInfo
import com.calypsan.listenup.core.IODispatcher
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.device.DeviceInfoProvider
import com.calypsan.listenup.client.data.sync.PendingOperationQueue
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.download.AppleDownloadEnqueuer
import com.calypsan.listenup.client.download.DownloadEnqueuer
import com.calypsan.listenup.client.download.DownloadFileManager
import com.calypsan.listenup.client.download.DownloadService
import com.calypsan.listenup.client.download.AppleDownloadService
import com.calypsan.listenup.client.playback.AudioTokenProvider
import com.calypsan.listenup.client.playback.CachedAudioTokenProvider
import com.calypsan.listenup.client.playback.ListeningEventRecorder
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
 * The native Swift `PlayerCoordinator` owns playback orchestration; this module
 * provides only the shared-domain seam it consumes:
 * - AudioTokenProvider for authenticated streaming
 * - DownloadService for offline downloads
 * - PlaybackPreparer — stateless playback-preparation pipeline
 * - ProgressTracker — position persistence + server sync
 * - SleepTimerManager — sleep-timer state
 */
val iosPlaybackModule: Module =
    module {
        // Playback-scoped coroutine scope
        single(qualifier = named("playbackScope")) {
            CoroutineScope(SupervisorJob() + IODispatcher)
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

        // Progress tracker — consumed directly by the native Swift PlayerCoordinator
        single {
            ProgressTracker(
                downloadRepository = get(),
                listeningEventRepository = get(),
                positionRepository = get(),
                scope = get(qualifier = named("playbackScope")),
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

        // Listening event recorder — span state machine for P2 listening history
        single {
            ListeningEventRecorder(
                listeningEventDao = get<ListenUpDatabase>().listeningEventDao(),
                tentativeSpanDao = get<ListenUpDatabase>().tentativeSpanDao(),
                enqueue = { domainName, entityId, opType, payload, ownerUserId ->
                    get<PendingOperationQueue>().enqueue(domainName, entityId, opType, payload, ownerUserId)
                },
                currentUserId = { get<AuthSession>().getUserId() },
                deviceInfo = get(),
            )
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
                playbackApi = null,
                capabilityDetector = null,
                syncApi = get(),
                scope = get(qualifier = named("playbackScope")),
                bookRepository = get(),
            )
        }

        // Sleep timer manager — observed via SKIE by PlayerCoordinator
        single {
            SleepTimerManager(
                scope = get(qualifier = named("playbackScope")),
            )
        }

        // Background sync scheduler
        single<BackgroundSyncScheduler> { IosBackgroundSyncScheduler() }
    }
