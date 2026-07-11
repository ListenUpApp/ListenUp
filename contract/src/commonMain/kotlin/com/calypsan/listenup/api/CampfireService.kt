package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.campfire.CampfireControlMode
import com.calypsan.listenup.api.dto.campfire.CampfireFrame
import com.calypsan.listenup.api.dto.campfire.CampfireId
import com.calypsan.listenup.api.dto.campfire.CampfireInvitableUser
import com.calypsan.listenup.api.dto.campfire.CampfireSettings
import com.calypsan.listenup.api.dto.campfire.CampfireSnapshot
import com.calypsan.listenup.api.dto.campfire.OpenCampfireSummary
import com.calypsan.listenup.api.dto.campfire.PlaybackCommand
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.streaming.RpcEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.rpc.annotations.Rpc

/**
 * Campfire (co-listening) contract — 2 to 8 users listening to the same book together over
 * a server-authoritative anchor timeline. See the co-listening design spec (2026-07-09).
 *
 * Deliberately not a `SyncEngine` domain: campfires are ephemeral, in-memory, single-process
 * rooms (the `ActiveSessionRepository` presence pattern, not the durable/cursored/Room-mirrored
 * sync catalog). A server restart wipes every room; clients fall back to solo playback.
 *
 * `observeSession()` is a server-pushed [Flow] of [RpcEvent]-wrapped [CampfireFrame]s, following
 * the same template as `ScannerService.observeProgress()` — the server broadcasts a frame only on
 * state *change* (command applied, membership, host handoff, chat, reaction, end), never on a
 * schedule. Flow collection IS presence: cancelling it is what the server treats as the member
 * going away.
 *
 * Every fallible method gates on `BookAccessPolicy.canAccess` for the campfire's book and
 * membership for session-scoped operations, returning [com.calypsan.listenup.api.error.CampfireError]
 * on the typed failure path.
 *
 * **Lobby phase (2026-07-11 amendment).** Every room is born in
 * [com.calypsan.listenup.api.dto.campfire.CampfirePhase.LOBBY] — chat and reactions work, but
 * [sendCommand] rejects playback commands with `CampfireError.NotStarted` until the host calls
 * [startSession], which transitions the room to
 * [com.calypsan.listenup.api.dto.campfire.CampfirePhase.LIVE] and broadcasts the shared start
 * moment as a [CampfireFrame.CampfireStarted] frame.
 */
@Rpc
interface CampfireService {
    /** Starts a new campfire for [bookId]. Gated by book access; the creator becomes host. */
    suspend fun createSession(
        bookId: String,
        settings: CampfireSettings,
    ): AppResult<CampfireSnapshot>

    /**
     * Joins an existing campfire, gated by the same book-access check as [createSession].
     * The returned snapshot carries the data needed for the client-side spoiler-ahead check
     * (see [CampfireSnapshot.yourPositionMs] and [CampfireSnapshot.spoilerAhead]).
     */
    suspend fun joinSession(sessionId: CampfireId): AppResult<CampfireSnapshot>

    /** Leaves the campfire. Broadcasts a [CampfireFrame.MemberLeft] frame to the remaining members. */
    suspend fun leaveSession(sessionId: CampfireId): AppResult<Unit>

    /**
     * Starts the campfire — [com.calypsan.listenup.api.dto.campfire.CampfirePhase.LOBBY] ->
     * [com.calypsan.listenup.api.dto.campfire.CampfirePhase.LIVE]. Re-anchors playback to now
     * (position unchanged, playing) and broadcasts a single [CampfireFrame.CampfireStarted] frame
     * to every member — the shared moment everyone begins from. Host only; only valid while the
     * campfire is still in the lobby phase.
     */
    suspend fun startSession(sessionId: CampfireId): AppResult<Unit>

    /**
     * Updates the campfire's settings (name, control mode, privacy, invited users) while it's
     * still in the lobby phase. Host only. Newly-added [com.calypsan.listenup.api.dto.campfire.CampfireSettings.invitedUserIds]
     * are push-notified exactly like [createSession]'s initial invite list; users already invited
     * are not re-notified. Returns the refreshed snapshot.
     */
    suspend fun updateSettings(
        sessionId: CampfireId,
        settings: CampfireSettings,
    ): AppResult<CampfireSnapshot>

    /** Ends the campfire for everyone. Host only. */
    suspend fun endSession(sessionId: CampfireId): AppResult<Unit>

    /** Hands the host role to [toUserId], who must already be a member. */
    suspend fun transferHost(
        sessionId: CampfireId,
        toUserId: String,
    ): AppResult<Unit>

    /** Switches the campfire between host-only and everyone-controls playback. */
    suspend fun setControlMode(
        sessionId: CampfireId,
        mode: CampfireControlMode,
    ): AppResult<Unit>

    /**
     * The live downlink for [sessionId] — every [CampfireFrame] from the moment of
     * subscription onward. Multiple clients may subscribe simultaneously; all see the same
     * events (a broadcast, per-room `SharedFlow`).
     */
    fun observeSession(sessionId: CampfireId): Flow<RpcEvent<CampfireFrame>>

    /**
     * Sends a playback command. The server validates membership and control mode, applies the
     * command, and broadcasts the resulting anchor to every member including the sender (who
     * applies it idempotently by echo-matching [com.calypsan.listenup.api.dto.campfire.PlaybackCommand.commandId]).
     * Rejected with `CampfireError.NotStarted` while the campfire is still in
     * [com.calypsan.listenup.api.dto.campfire.CampfirePhase.LOBBY] — see [startSession].
     */
    suspend fun sendCommand(
        sessionId: CampfireId,
        command: PlaybackCommand,
    ): AppResult<Unit>

    /** Sends a chat message, broadcast to all current subscribers and folded into the room's ring buffer. */
    suspend fun sendChat(
        sessionId: CampfireId,
        text: String,
    ): AppResult<Unit>

    /** Sends a fire-and-forget emoji reaction; not persisted in the snapshot, rate-limited per member. */
    suspend fun sendReaction(
        sessionId: CampfireId,
        emoji: String,
    ): AppResult<Unit>

    /** Lists open (non-invite-only) campfires visible to the caller, ACL-filtered like `SocialService.currentlyListening`. */
    suspend fun listOpenSessions(): AppResult<List<OpenCampfireSummary>>

    /**
     * The create/invite sheet's user picker: every user (other than the caller) who can already
     * access [bookId] — an invite to a book the recipient can't see would be a dead-end deep
     * link (design spec §7). The candidate list is filtered server-side so the client never
     * receives the full user roster just to build an invite picker.
     */
    suspend fun listInvitableUsers(bookId: String): AppResult<List<CampfireInvitableUser>>
}
