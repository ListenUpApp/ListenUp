package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.playback.PlaybackManager
import com.calypsan.listenup.client.playback.PlaybackManagerImpl
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * Desktop `PlaybackManager` wiring. Lives in `:sharedLogic` so the binding can construct
 * `PlaybackManagerImpl` against the `internal` Room DAOs. The `playbackScope` qualifier is
 * provided by the desktop UI platform module; resolved at runtime from the merged graph.
 */
val desktopPlaybackModule: Module =
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
                scope = get(qualifier = named("playbackScope")),
                bookIngestPort = get(),
            )
        }
    }
