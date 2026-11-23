package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.playback.ManagedPlayerEngine
import com.calypsan.listenup.client.playback.NoOutputRoute
import com.calypsan.listenup.client.playback.NoPlaybackStartGate
import com.calypsan.listenup.client.playback.OutputRoute
import com.calypsan.listenup.client.playback.PlaybackManager
import com.calypsan.listenup.client.playback.PlaybackManagerImpl
import com.calypsan.listenup.client.playback.PlaybackProgressReporter
import com.calypsan.listenup.client.playback.PlaybackStartGate
import com.calypsan.listenup.client.playback.PlaybackStateProvider
import com.calypsan.listenup.client.playback.PlayerEngine
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

private const val PLAYBACK_SCOPE = "playbackScope"

/**
 * Desktop `PlaybackManager` wiring. Lives in `:app:sharedLogic` so the binding can construct
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
                scope = get(qualifier = named(PLAYBACK_SCOPE)),
                localPreferences = get(),
            )
        }

        playbackPreparerSingle(named(PLAYBACK_SCOPE))

        single<PlaybackManager> {
            PlaybackManagerImpl(
                preparer = get(),
                reporter = get(),
                scope = get(qualifier = named(PLAYBACK_SCOPE)),
                playbackBandwidthCoordinator = get(),
            )
        } bind PlaybackStateProvider::class

        // The shared player's engine (playerModule): today's PlaybackManager + PlaybackController behind one seam.
        single(named(OFFLINE_COPIES)) { true }
        single<PlaybackStartGate> { NoPlaybackStartGate }
        single<OutputRoute> { NoOutputRoute }
        single<PlayerEngine> {
            ManagedPlayerEngine(
                playbackManager = get(),
                playbackController = get(),
                startGate = get(),
                progressTracker = get(),
                outputRoute = get(),
                scope = get(qualifier = named(PLAYBACK_SCOPE)),
            )
        }
    }
