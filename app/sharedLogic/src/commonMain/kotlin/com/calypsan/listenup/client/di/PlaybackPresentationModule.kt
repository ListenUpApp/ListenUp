package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.presentation.nowplaying.NowPlayingViewModel
import org.koin.dsl.module

/**
 * Koin module providing the playback presentation layer.
 *
 * Currently exposes [NowPlayingViewModel] as the single playback VM consumed by
 * Android, Desktop, and iOS.
 *
 * Bound as `single` — the deliberate, documented exception to the "VMs are `factory`" rule (the
 * guard test `no ViewModel is registered as a Koin singleton` allows exactly this one). Its `init`
 * acquires a process-singleton playback controller (`playbackController.acquire()`) and it has two
 * `koinViewModel()` consumers in separate nav stores (the shell mini-player and the document
 * viewer), so a `factory` would create two instances and double-acquire the controller. (The earlier
 * rationale here cited `LibraryViewModel` as a `single` precedent; that is stale — `LibraryViewModel`
 * is now a `factory`, because a singleton VM zombies when its owning store is cleared.) The correct
 * end state is to extract the controller acquisition into a singleton service so this VM can become a
 * pure `factory` projection too — tracked in `docs/superpowers/followups.md`.
 *
 * `viewModelOf` is not used because it ships in `koin-compose-viewmodel`, which is not on the
 * shared classpath; `factory { }` is the commonMain equivalent.
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
            )
        }
    }
