package com.calypsan.listenup.client.di

import com.calypsan.listenup.core.IODispatcher
import com.calypsan.listenup.client.data.remote.PlaybackApi
import com.calypsan.listenup.client.data.remote.PlaybackApiContract
import com.calypsan.listenup.client.features.bookdetail.BookDetailPlatformActions
import com.calypsan.listenup.client.features.bookdetail.DesktopBookDetailPlatformActions
import com.calypsan.listenup.client.download.DownloadEnqueuer
import com.calypsan.listenup.client.download.DownloadFileManager
import com.calypsan.listenup.client.download.DownloadService
import com.calypsan.listenup.client.download.JvmDownloadEnqueuer
import com.calypsan.listenup.client.platform.DesktopAudioTokenProvider
import com.calypsan.listenup.client.platform.StubBackgroundSyncScheduler
import com.calypsan.listenup.client.platform.StubDownloadService
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.sync.PendingOperationQueue
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.playback.AudioCapabilityDetector
import com.calypsan.listenup.client.playback.AudioPlayer
import com.calypsan.listenup.client.playback.AudioTokenProvider
import com.calypsan.listenup.client.playback.DesktopPlaybackController
import com.calypsan.listenup.client.playback.ListeningEventRecorder
import com.calypsan.listenup.client.playback.PlaybackController
import com.calypsan.listenup.client.playback.PlaybackManager
import com.calypsan.listenup.client.playback.PlaybackManagerImpl
import com.calypsan.listenup.client.playback.ProgressTracker
import com.calypsan.listenup.client.playback.SleepTimerManager
import com.calypsan.listenup.client.playback.FfmpegAudioPlayer
import com.calypsan.listenup.client.playback.FfmpegCapabilityDetector
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
 * - AudioCapabilityDetector for codec reporting
 * - DownloadService (stub - downloads not yet implemented)
 * - BackgroundSyncScheduler (stub - relies on SSE while running)
 * - PlaybackManager for playback orchestration
 * - ProgressTracker for position persistence
 *
 * NowPlayingViewModel is bound in the shared `playbackPresentationModule`.
 */
val platformModule: Module =
    module {
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

        // Audio capability detector (FFmpeg decodes all formats)
        single<AudioCapabilityDetector> { FfmpegCapabilityDetector() }

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

        // DownloadEnqueuer seam — Desktop no-op (downloads not supported)
        single<DownloadEnqueuer> { JvmDownloadEnqueuer() }

        // Playback API for codec negotiation
        single<PlaybackApiContract> { PlaybackApi(clientFactory = get()) }

        // Progress tracker
        single {
            ProgressTracker(
                downloadRepository = get(),
                listeningEventRepository = get(),
                positionRepository = get(),
                scope = get(qualifier = named("playbackScope")),
            )
        }

        // Listening event recorder — span state machine for P2 listening history
        single {
            ListeningEventRecorder(
                listeningEventDao = get<ListenUpDatabase>().listeningEventDao(),
                tentativeSpanDao = get<ListenUpDatabase>().tentativeSpanDao(),
                enqueue = { domainName, entityId, opType, payload, ownerUserId ->
                    get<PendingOperationQueue>().enqueue(domainName, entityId, opType, payload, ownerUserId)
                    Unit
                },
                currentUserId = { get<AuthSession>().getUserId() },
                // JVM/desktop: no safe Java-free way to get device name; null is fine (advisory column).
                deviceLabel = { null },
            )
        }

        // Sleep timer manager
        single {
            SleepTimerManager(
                scope = get(qualifier = named("playbackScope")),
            )
        }

        // Playback manager (with codec negotiation enabled)
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
                playbackApi = get(),
                capabilityDetector = get(),
                syncApi = get(),
                deviceContext = get(),
                scope = get(qualifier = named("playbackScope")),
                bookRepository = get(),
            )
        }

        // Background sync scheduler (stub)
        single<BackgroundSyncScheduler> { StubBackgroundSyncScheduler() }

        // Book detail platform actions (playback via FFmpeg, downloads stubbed)
        single<BookDetailPlatformActions> {
            DesktopBookDetailPlatformActions(nowPlayingViewModel = get())
        }
    }
