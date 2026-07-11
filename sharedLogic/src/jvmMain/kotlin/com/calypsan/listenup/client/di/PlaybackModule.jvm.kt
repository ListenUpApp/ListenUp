package com.calypsan.listenup.client.di

import com.calypsan.listenup.api.BookService
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.client.data.remote.rpcChannel
import com.calypsan.listenup.client.data.sync.SyncDomainHandler
import com.calypsan.listenup.client.playback.PlaybackManager
import com.calypsan.listenup.client.playback.PlaybackManagerImpl
import com.calypsan.listenup.client.playback.PlaybackProgressReporter
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
        // Position reporter for the PlaybackManagerImpl seam. Desktop has no Media3
        // `PlaybackService`, so the reporter is the only driver of listening-event
        // recording here — it is bound WITH the recorder.
        single {
            PlaybackProgressReporter(
                progressTracker = get(),
                recorder = get(),
                scope = get(qualifier = named("playbackScope")),
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
                channel = rpcChannel<BookService>(),
                scope = get(qualifier = named("playbackScope")),
                bookSyncDomainHandler = get<SyncDomainHandler<BookSyncPayload>>(named(SyncDomains.BOOKS.name)),
                playbackBandwidthCoordinator = get(),
            )
        }
    }
