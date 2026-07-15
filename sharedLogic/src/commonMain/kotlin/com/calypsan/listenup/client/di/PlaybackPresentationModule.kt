package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.campfire.ActiveCampfireCoordinator
import com.calypsan.listenup.client.presentation.nowplaying.NowPlayingViewModel
import org.koin.dsl.module

/**
 * Koin module providing the playback presentation layer.
 *
 * Currently exposes [NowPlayingViewModel] as the single playback VM consumed by
 * Android, Desktop, and iOS. Bound as `single` to survive recomposition;
 * lifecycle matches the process.
 *
 * `viewModelOf` is not used because it ships in `koin-compose-viewmodel`, which is not on
 * the shared classpath. The `single` scope is the same precedent as `LibraryViewModel`
 * in `presentationModule` (a hoisted app-session VM).
 */
internal val playbackPresentationModule =
    module {
        single {
            NowPlayingViewModel(
                playbackManager = get(),
                bookRepository = get(),
                sleepTimerManager = get(),
                playbackController = get(),
                playbackPreferences = get(),
                networkMonitor = get(),
                documentRepository = get(),
                downloadRepository = get(),
                playbackPositionRepository = get(),
                // The coordinator (single) owns the process-scope liveness mirror; the VM only needs
                // to read it. Resolving it here also instantiates the coordinator so its mirror starts.
                activeCampfire = get<ActiveCampfireCoordinator>().current,
            )
        }
    }
