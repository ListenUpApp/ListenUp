package com.calypsan.listenup.client.di

import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.client.data.sync.SyncDomainHandler
import com.calypsan.listenup.client.playback.PlaybackManager
import com.calypsan.listenup.client.playback.PlaybackManagerImpl
import com.calypsan.listenup.client.playback.PlaybackProgressReporter
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * Android `PlaybackManager` wiring. Lives in `:sharedLogic` (not `:sharedUI`) so the
 * binding can construct `PlaybackManagerImpl` against the now-`internal` Room DAOs —
 * same-module visibility. Registered by the Android app's Koin start (`ListenUp.kt`).
 */
val androidPlaybackModule: Module =
    module {
        // Position reporter for the PlaybackManagerImpl seam. Android binds NO recorder:
        // `PlaybackService` already drives `ListeningEventRecorder` directly to integrate
        // with Media3, and those same Playing/Paused signals also reach PlaybackManagerImpl
        // via `MediaControllerHolder`. A null recorder here prevents a second recording.
        single {
            PlaybackProgressReporter(
                progressTracker = get(),
                recorder = null,
                scope = get(),
            )
        }

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
                deviceContext = get(),
                downloadService = get(),
                playbackRpcFactory = get(),
                bookRpcFactory = get(),
                scope = get(),
                bookSyncDomainHandler = get<SyncDomainHandler<BookSyncPayload>>(named(SyncDomains.BOOKS.name)),
                playbackBandwidthCoordinator = get(),
            )
        }
    }
