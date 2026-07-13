package com.calypsan.listenup.client.campfire

import com.calypsan.listenup.api.dto.campfire.CampfireId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
 */
data class ActiveCampfire(
    val sessionId: CampfireId,
    val bookId: String,
    val isHost: Boolean,
)

/**
 * Process-scope holder for the currently-live [ActiveCampfire], or `null` when none.
 *
 * The seam that lets the process-singleton `NowPlayingViewModel` observe campfire liveness without
 * depending on the per-session `CampfireSessionController`/`CampfireViewModel` factories (co-listening
 * coexistence spec, B3). The per-session `CampfireViewModel` is the sole writer — it mirrors its
 * controller's hot state into [set]; readers observe [current]. Registered as a Koin `single`.
 */
class ActiveCampfireCoordinator {
    /** The live campfire, or `null` when no session is active. */
    val current: StateFlow<ActiveCampfire?>
        field = MutableStateFlow<ActiveCampfire?>(null)

    /** Publishes the live campfire (or `null` to clear). Called by `CampfireViewModel` on every controller-state change. */
    fun set(value: ActiveCampfire?) {
        current.value = value
    }
}
