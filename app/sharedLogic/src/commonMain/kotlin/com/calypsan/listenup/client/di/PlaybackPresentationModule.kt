package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.playback.PlaybackControllerActivator
import com.calypsan.listenup.client.presentation.nowplaying.NowPlayingSheetState
import com.calypsan.listenup.client.presentation.nowplaying.NowPlayingViewModel
import org.koin.dsl.module

/**
 * Koin module providing the playback presentation layer.
 *
 * Exposes [NowPlayingViewModel] — the playback VM consumed by Android and Desktop (iOS drives
 * playback through its own native `PlayerCoordinator`, not this module) — plus the two
 * process-lifetime collaborators that let it be a plain `factory` like every other ViewModel:
 *
 * - [PlaybackControllerActivator] acquires the `PlaybackController` connection exactly once, the
 *   first time something resolves it. It is deliberately NOT `createdAtStart`: on Android the first
 *   acquire binds `PlaybackService`, which builds ExoPlayer and the MediaSession, and doing that at
 *   Koin startup put it ahead of the first frame and on every FCM or WorkManager wake. Android
 *   resolves it from the two places a controller is actually needed — `MainActivity` (after its
 *   first frame) and `PlaybackService.onCreate` (a media button, Android Auto or a resumption
 *   request starting the process with no activity). Desktop's `acquire()` is a no-op, so nothing
 *   there needs to resolve it. This used to happen in `NowPlayingViewModel.init`, which
 *   forced the VM itself to be a `single` — the app's one documented exception to the "VMs are
 *   `factory`" rule — because a `factory` would create a fresh instance (and double-acquire the
 *   controller) at each of its two `koinViewModel()` consumers (the shell mini-player and the
 *   document viewer). Extracting acquisition here removes that constraint entirely.
 * - [NowPlayingSheetState] holds the sheet's expand/collapse `StateFlow` so every VM instance —
 *   now one per owning store — reads and writes the SAME expansion state, rather than each
 *   instance owning its own (which would desync the shell and document-viewer sheets).
 *
 * Binding [NowPlayingViewModel] as a `single` was the proximate cause of a zombie-VM bug: when
 * either owning `ViewModelStore` cleared (an overnight Activity destroy with the process
 * retained; popping the document viewer), `onCleared()` permanently cancelled the singleton's
 * `viewModelScope`, and Koin kept re-serving that same dead instance forever — frozen play/pause
 * icon, a back-swipe that fell through to the screen behind the sheet, and a mini player that
 * never came back. As a plain `factory`, a store clearing kills only that store's VM instance;
 * the next `koinViewModel()` call builds a fresh one over the live singletons above, so playback
 * command state, expansion state, and the controller connection are all correct by construction.
 *
 * `viewModelOf` is not used because it ships in `koin-compose-viewmodel`, which is not on the
 * shared classpath; `factory { }` is the commonMain equivalent.
 */
internal val playbackPresentationModule =
    module {
        single { NowPlayingSheetState() }

        single { PlaybackControllerActivator(playbackController = get()) }

        factory {
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
                sheetState = get(),
                errorBus = get(),
            )
        }
    }
