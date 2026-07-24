package com.calypsan.listenup.client.di

import com.calypsan.listenup.api.dto.auth.DeviceInfo
import com.calypsan.listenup.core.IODispatcher
import com.calypsan.listenup.client.features.bookdetail.BookDetailPlatformActions
import com.calypsan.listenup.client.features.bookdetail.DesktopBookDetailPlatformActions
import com.calypsan.listenup.client.download.DownloadFileManager
import com.calypsan.listenup.client.download.DownloadService
import com.calypsan.listenup.client.platform.DesktopAudioTokenProvider
import com.calypsan.listenup.client.platform.StubBackgroundSyncScheduler
import com.calypsan.listenup.client.platform.StubDownloadService
import com.calypsan.listenup.client.device.DeviceInfoProvider
import com.calypsan.listenup.client.playback.AudioPlayer
import com.calypsan.listenup.client.playback.AudioTokenProvider
import com.calypsan.listenup.client.playback.DesktopPlaybackController
import com.calypsan.listenup.client.playback.PlaybackController
import com.calypsan.listenup.client.playback.ProgressTracker
import com.calypsan.listenup.client.playback.SleepTimerManager
import com.calypsan.listenup.client.playback.FfmpegAudioPlayer
import com.calypsan.listenup.client.sync.BackgroundSyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.util.UUID

/**
 * Desktop platform module for Koin.
 *
 * Provides desktop-specific implementations of platform abstractions:
 * - FFmpeg-based audio playback (decodes all formats, no system deps)
 * - AudioTokenProvider for auth token management
 * - DownloadService (stub - downloads not yet implemented)
 * - BackgroundSyncScheduler (stub - relies on the live firehose while running)
 * - PlaybackManager for playback orchestration
 * - ProgressTracker for position persistence
 *
 * NowPlayingViewModel is bound in the shared `playbackPresentationModule`.
 */
val platformModule: Module =
    module {
        // Platform capability flag: Desktop does not support download and playback
        // (StubDownloadService + no-op controller), so BookAvailability gates those paths off.
        single(qualifier = named("playbackAvailable")) { false }

        // Application-scoped coroutine scope for background operations
        single {
            CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }

        // Playback-scoped coroutine scope
        single(qualifier = named("playbackScope")) {
            CoroutineScope(SupervisorJob() + IODispatcher)
        }

        // Device ID for listening events
        // Desktop uses a persistent UUID based on machine characteristics
        single(qualifier = named("deviceId")) {
            val hostname =
                System.getenv("HOSTNAME")
                    ?: System.getenv("COMPUTERNAME")
                    ?: "desktop"
            val username = System.getProperty("user.name", "user")
            UUID.nameUUIDFromBytes("$hostname:$username".toByteArray()).toString()
        }

        // File manager for downloads
        single { DownloadFileManager(storagePaths = get()) }

        // Audio token provider
        single<AudioTokenProvider> {
            DesktopAudioTokenProvider(authSession = get())
        }

        // Audio player (FFmpeg decoder + javax.sound output)
        single<AudioPlayer> {
            FfmpegAudioPlayer(
                tokenProvider = get(),
                scope = get(qualifier = named("playbackScope")),
            )
        }

        // Playback controller seam (delegates to AudioPlayer for command-side operations)
        single<PlaybackController> {
            DesktopPlaybackController(
                audioPlayer = get(),
                playbackManager = get(),
            )
        }

        // Download service (stub)
        single<DownloadService> { StubDownloadService() }

        // DownloadEnqueuer seam (Desktop no-op) is bound in `:app:sharedLogic`'s
        // `desktopDownloadModule` so the now-`internal` `DownloadEntity` need not be public.

        // Progress tracker
        single {
            ProgressTracker(
                downloadRepository = get(),
                positionRepository = get(),
                scope = get(qualifier = named("playbackScope")),
            )
        }

        // Structured device identity — shared source for auth login + listening history.
        single<DeviceInfoProvider> {
            val clientVersion = get<String>(named("clientVersion"))
            DeviceInfoProvider {
                DeviceInfo(
                    deviceType = "desktop",
                    platform = System.getProperty("os.name"),
                    clientName = "ListenUp Desktop",
                    clientVersion = clientVersion,
                    deviceName = System.getProperty("user.name"),
                )
            }
        }

        // Sleep timer manager
        single {
            SleepTimerManager(
                scope = get(qualifier = named("playbackScope")),
            )
        }

        // Background sync scheduler (stub)
        single<BackgroundSyncScheduler> { StubBackgroundSyncScheduler() }

        // Book detail platform actions (playback via FFmpeg, downloads stubbed)
        single<BookDetailPlatformActions> {
            DesktopBookDetailPlatformActions(nowPlayingViewModel = get())
        }
    }
