package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.presentation.nowplaying.NowPlayingViewModel
import org.koin.dsl.module

/**
 * Koin module providing the playback presentation layer.
 *
 * Currently exposes [NowPlayingViewModel] as the single playback VM consumed by
 * Android, Desktop, and (post-W10) iOS. Bound as `single` per the W7 Phase E2.2.2
 * decision to survive recomposition; lifecycle matches the process.
 *
 * `viewModelOf` is not used because it ships in `koin-compose-viewmodel`, which is not on
 * the shared classpath. The `single` scope is the same precedent as `LibraryViewModel`
 * in `presentationModule` (a hoisted app-session VM).
 */
val playbackPresentationModule =
    module {
        single {
            NowPlayingViewModel(
                playbackManager = get(),
                bookRepository = get(),
                sleepTimerManager = get(),
                playbackController = get(),
                playbackPreferences = get(),
                networkMonitor = get(),
            )
        }
    }
