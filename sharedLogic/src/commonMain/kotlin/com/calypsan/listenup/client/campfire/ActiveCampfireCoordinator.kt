package com.calypsan.listenup.client.campfire

import com.calypsan.listenup.api.dto.campfire.CampfireId
import com.calypsan.listenup.api.dto.campfire.CampfirePhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * A snapshot of the one live Campfire session, as the rest of the app needs to see it.
 *
 * Deliberately a flat value (no controller reference, no closures): the process-singleton
 * [com.calypsan.listenup.client.presentation.nowplaying.NowPlayingViewModel] reads this to decide
 * whether playing a *different* book must confirm-and-exit the campfire first (co-listening
 * coexistence spec, B3). Only one campfire is live at a time, as everywhere else in the feature.
 *
 * @property sessionId The live session's id.
 * @property bookId The book the campfire is on. Playing this same book is never guarded.
 * @property isHost Whether the local user hosts the session — selects the "end" vs "leave" confirm copy.
 * @property phase Whether the fire has been lit yet. Playing the campfire's own book while still in
 * [CampfirePhase.LOBBY] re-expands the lobby instead of starting solo playback (F6 — "no playback
 * before the fire is lit" per PR #1101).
 */
internal data class ActiveCampfire(
    val sessionId: CampfireId,
    val bookId: String,
    val isHost: Boolean,
    val phase: CampfirePhase,
)

/**
 * Process-scope holder for the currently-live [ActiveCampfire], or `null` when none.
 *
 * The seam that lets the process-singleton `NowPlayingViewModel` observe campfire liveness without
 * depending on the per-screen `CampfireViewModel` (co-listening coexistence spec, B3). Registered as
 * a Koin `single` alongside the now-`single` [CampfireSessionController]: the coordinator OWNS the
 * always-on mirror at **process scope** (the injected app-lifetime [CoroutineScope]) rather than a
 * ViewModel's `viewModelScope`. That is the F2 ownership fix — an activity torn down and rebuilt
 * (task-swipe while the playback service keeps the process alive) no longer stops the mirror or
 * strands the live session behind a fresh, empty ViewModel; readers keep seeing the true liveness.
 */
internal class ActiveCampfireCoordinator(
    controller: CampfireSessionController,
    scope: CoroutineScope,
) {
    /** The live campfire, or `null` when no session is active. */
    val current: StateFlow<ActiveCampfire?>
        field = MutableStateFlow<ActiveCampfire?>(null)

    init {
        // Always-on, process-scope mirror of the single controller's hot state. Independent of any
        // ViewModel subscription: the singleton NowPlayingViewModel must see liveness at all times.
        scope.launch {
            controller.state.collect { current.value = it.toActiveCampfire() }
        }
    }
}

/** Only a live/lobby session is guard-worthy; Idle / Joining / Disconnected / Ended → `null`. */
private fun CampfireUiState.toActiveCampfire(): ActiveCampfire? =
    if (this is CampfireUiState.Active) {
        ActiveCampfire(sessionId = sessionId, bookId = bookId, isHost = isHost, phase = phase)
    } else {
        null
    }
