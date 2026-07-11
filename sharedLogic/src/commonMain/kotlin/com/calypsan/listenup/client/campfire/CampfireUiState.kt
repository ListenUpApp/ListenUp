package com.calypsan.listenup.client.campfire

import com.calypsan.listenup.api.dto.campfire.CampfireAnchor
import com.calypsan.listenup.api.dto.campfire.CampfireControlMode
import com.calypsan.listenup.api.dto.campfire.CampfireId
import com.calypsan.listenup.api.dto.campfire.CampfireInvitableUser
import com.calypsan.listenup.api.dto.campfire.CampfireMember
import com.calypsan.listenup.api.dto.campfire.CampfirePhase
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
     * [com.calypsan.listenup.api.dto.campfire.CampfireSnapshot]; [hasControl], [isHost], and
     * [pendingRejoinSync] are locally-derived UI concerns with no wire equivalent.
     *
     * @property phase The room's lifecycle phase — no local playback is applied while
     * [CampfirePhase.LOBBY] (the 2026-07-11 lobby amendment); see
     * [CampfireSessionController.join]'s KDoc.
     * @property name The campfire's display name.
     * @property hasControl Whether the local caller may currently send
     * [com.calypsan.listenup.api.dto.campfire.PlaybackCommand]s — `true` when [controlMode] is
     * [CampfireControlMode.EVERYONE], or the caller is [hostUserId] under
     * [CampfireControlMode.HOST_ONLY]. Meaningless while [phase] is [CampfirePhase.LOBBY]
     * (playback commands are rejected outright — see [CampfireSessionController]'s `sendCommand`).
     * @property isHost Whether the local caller is [hostUserId] — distinct from [hasControl],
     * which also reads `true` for non-hosts under [CampfireControlMode.EVERYONE]. Drives the
     * lobby's host-only "Start" affordance.
     * @property startedAtEpochMs Epoch-ms the host called
     * [com.calypsan.listenup.api.CampfireService.startSession], or `null` while still in
     * [CampfirePhase.LOBBY].
     * @property invitedPending Invited-but-not-yet-joined users for the lobby roster — shrinks as
     * [CampfireFrame.MemberJoined] frames arrive.
     * @property inviteOnly Mirrors [com.calypsan.listenup.api.dto.campfire.CampfireSettings.inviteOnly] —
     * the live Room's reinvite affordance (task L3) needs this to preserve the room's membership
     * boundary when it calls [CampfireSessionController.updateSettings] with a merged invite list.
     * @property pendingRejoinSync Non-null only immediately after [CampfireSessionController.rejoin]
     * detects the room has drifted far enough from local playback that auto-applying it would be
     * jarring (or spoiler-inducing) — the confirm-dialog case. Cleared by
     * [CampfireSessionController.confirmRejoinSync].
     */
    data class Active(
        val sessionId: CampfireId,
        val bookId: String,
        val phase: CampfirePhase,
        val name: String,
        val anchor: CampfireAnchor,
        val members: List<CampfireMember>,
        val hostUserId: String,
        val controlMode: CampfireControlMode,
        val chat: List<ChatMessage>,
        val yourPositionMs: Long?,
        val spoilerAhead: Boolean,
        val hasControl: Boolean,
        val isHost: Boolean,
        val startedAtEpochMs: Long? = null,
        val invitedPending: List<CampfireInvitableUser> = emptyList(),
        val inviteOnly: Boolean = false,
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

    /**
     * The local caller attempted a playback command while the campfire is still in
     * [CampfirePhase.LOBBY] — nothing has started yet, so there's no shared anchor to control
     * (the 2026-07-11 lobby amendment). Mirrors [ControlDenied]'s shape.
     */
    data object NotStarted : CampfireSessionEvent

    /** A fire-and-forget reaction arrived — never folded into [CampfireUiState], rendered as a transient overlay. */
    data class ReactionReceived(
        val userId: String,
        val emoji: String,
    ) : CampfireSessionEvent
}
