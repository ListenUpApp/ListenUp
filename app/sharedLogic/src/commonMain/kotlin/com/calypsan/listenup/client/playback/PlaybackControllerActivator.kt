package com.calypsan.listenup.client.playback

/**
 * Acquires the process-lifetime [PlaybackController] connection at Koin startup.
 *
 * Bound `single(createdAtStart = true)` in `playbackPresentationModule`, so [PlaybackController.acquire]
 * runs exactly once per process, independent of any ViewModelStore. This used to live in
 * `NowPlayingViewModel.init`, which forced that ViewModel to be a Koin `single` — the only
 * ViewModel in the app not scoped per-store — because a `factory` VM would double-acquire across
 * its two `koinViewModel()` consumers (the shell mini-player and the document viewer). Acquiring
 * here instead lets [com.calypsan.listenup.client.presentation.nowplaying.NowPlayingViewModel] be
 * a plain `factory` like every other ViewModel, closing the zombie-VM hazard: previously, when
 * either owning store cleared, `onCleared()` permanently cancelled the singleton's
 * `viewModelScope`, and Koin kept re-serving the same dead instance forever (frozen play/pause
 * icon, dead back-swipe, a mini player that never reappeared).
 *
 * [PlaybackController.acquire] is refcounted and idempotent on Android (see
 * `MediaControllerHolder`) and a no-op on Desktop/Apple, so acquiring once here — with no matching
 * `release()` — is safe: the connection is established on first acquire and held for the
 * process's lifetime, which is exactly the intended lifecycle.
 */
internal class PlaybackControllerActivator(
    playbackController: PlaybackController,
) {
    init {
        playbackController.acquire()
    }
}
