package com.calypsan.listenup.client.campfire

import com.calypsan.listenup.api.dto.campfire.CampfireAnchor
import com.calypsan.listenup.api.dto.campfire.CampfireControlMode
import com.calypsan.listenup.api.dto.campfire.CampfireId
import com.calypsan.listenup.api.dto.campfire.CampfireMember
import com.calypsan.listenup.api.dto.campfire.ChatMessage

/**
 * UI state surface for [CampfireSessionController] (Task 8) — the client's per-session
 * "session brain" for a co-listening room. A per-screen sealed hierarchy per the house
 * rubric rule ("Key Rubric Rules") rather than a flat `data class` with nullable fields
 * for every possible session phase.
 *
 * `internal`: consumed only from within `:sharedLogic` (the controller and its
 * presentation-layer callers, once Task 9/10 exist), so it never needs to cross the
 * export surface.
 */
internal sealed interface CampfireUiState {
    /** No session joined. */
    data object Idle : CampfireUiState

    /** [CampfireSessionController.join] (or [CampfireSessionController.rejoin]) is in flight. */
    data class Joining(
        val sessionId: CampfireId,
    ) : CampfireUiState

    /**
     * A live, connected session. Most fields mirror
     * [com.calypsan.listenup.api.dto.campfire.CampfireSnapshot]; [hasControl] and
     * [pendingRejoinSync] are locally-derived UI concerns with no wire equivalent.
     *
     * @property hasControl Whether the local caller may currently send
     * [com.calypsan.listenup.api.dto.campfire.PlaybackCommand]s — `true` when [controlMode] is
     * [CampfireControlMode.EVERYONE], or the caller is [hostUserId] under
     * [CampfireControlMode.HOST_ONLY].
     * @property pendingRejoinSync Non-null only immediately after [CampfireSessionController.rejoin]
     * detects the room has drifted far enough from local playback that auto-applying it would be
     * jarring (or spoiler-inducing) — the confirm-dialog case. Cleared by
     * [CampfireSessionController.confirmRejoinSync].
     */
    data class Active(
        val sessionId: CampfireId,
        val bookId: String,
        val anchor: CampfireAnchor,
        val members: List<CampfireMember>,
        val hostUserId: String,
        val controlMode: CampfireControlMode,
        val chat: List<ChatMessage>,
        val yourPositionMs: Long?,
        val spoilerAhead: Boolean,
        val hasControl: Boolean,
        val pendingRejoinSync: CampfireAnchor? = null,
    ) : CampfireUiState

    /**
     * The live [com.calypsan.listenup.api.CampfireService.observeSession] stream ended
     * unexpectedly (server-side error, or the socket simply dropped) — NOT a graceful [Ended].
     * Per the Never Stranded principle, local playback is left running exactly as it was
     * ([keepPlayingSolo]); [CampfireSessionController.rejoin] re-fetches a fresh snapshot.
     */
    data class Disconnected(
        val sessionId: CampfireId,
        val keepPlayingSolo: Boolean = true,
    ) : CampfireUiState

    /** The session ended for everyone (host ended it, or the server's idle sweeper reaped it). */
    data class Ended(
        val sessionId: CampfireId,
        val reason: String,
    ) : CampfireUiState
}

/**
 * One-shot events emitted by [CampfireSessionController] that a UI consumes exactly once
 * (snackbar, transient overlay) rather than folding into [CampfireUiState] — the house
 * `Channel(Channel.BUFFERED).receiveAsFlow()` idiom (see [CampfireSessionController.events]).
 */
internal sealed interface CampfireSessionEvent {
    /** The local caller attempted a playback command while lacking control (HOST_ONLY, not host). */
    data object ControlDenied : CampfireSessionEvent

    /** A fire-and-forget reaction arrived — never folded into [CampfireUiState], rendered as a transient overlay. */
    data class ReactionReceived(
        val userId: String,
        val emoji: String,
    ) : CampfireSessionEvent
}
