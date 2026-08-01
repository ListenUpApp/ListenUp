package com.calypsan.listenup.client.presentation.nowplaying

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-lifetime holder for the Now Playing sheet's expand/collapse state.
 *
 * [NowPlayingViewModel] is a Koin `factory` — each `koinViewModel()` call site (the shell
 * mini-player's Activity-scoped store, the document viewer's nav-entry-scoped store) is served
 * its own instance. Expansion state must be visible to every one of them, so it lives here
 * instead of on the VM: a `factory`-scoped `MutableStateFlow` would desync the moment either
 * owning store is cleared and a fresh VM is served in its place — the new instance would start
 * collapsed even though the sheet was showing full-screen a moment ago from the other owner's
 * perspective.
 *
 * Bound as a Koin `single`. Starting collapsed on process start (and surviving Activity
 * recreation within a live process, since the singleton outlives any one Activity) is
 * deliberate — a fresh process should never open straight to the full-screen player.
 */
internal class NowPlayingSheetState {
    val isExpanded: StateFlow<Boolean>
        field = MutableStateFlow(false)

    fun expand() {
        isExpanded.value = true
    }

    fun collapse() {
        isExpanded.value = false
    }
}
