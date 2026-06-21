package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.playback.PlaybackManager
import com.calypsan.listenup.client.playback.PlaybackManagerImpl
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Android `PlaybackManager` wiring. Lives in `:sharedLogic` (not `:sharedUI`) so the
 * binding can construct `PlaybackManagerImpl` against the now-`internal` Room DAOs —
 * same-module visibility. Registered by the Android app's Koin start (`ListenUp.kt`).
 */
val androidPlaybackModule: Module =
    module {
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
                deviceContext = get(),
                downloadService = get(),
                playbackRpcFactory = get(),
                syncApi = get(),
                scope = get(),
                bookIngestPort = get(),
            )
        }
    }
