package com.calypsan.listenup.client.campfire

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

/**
 * Transport seam between `CampfireSessionController` (Task 8) and however a co-listening
 * session actually reaches the server. Mirrors [com.calypsan.listenup.api.CampfireService]
 * method-for-method — every fallible call already returns [AppResult] at the contract layer,
 * so this seam adds no extra wrapping, just a swappable binding.
 *
 * Two reasons this seam exists rather than the controller calling [CampfireRpcFactory]
 * directly:
 *  1. **Testability** — the session controller (drift correction, optimistic command
 *     application, presence handling) is exercised against a fake implementing this
 *     interface with in-memory state, no real RPC socket.
 *  2. **Transport swappability, by design, not improvisation** — the co-listening plan's
 *     long-lived bidirectional [observeSession] stream is the highest-risk kotlinx.rpc
 *     surface in the app (see the campfire implementation plan's soak test and STOP
 *     conditions). If long-lived RPC flows misbehave under load in practice, an SSE-based
 *     transport can implement this same interface without the controller changing at all.
 *
 * Contract DTOs ([CampfireSnapshot], [CampfireFrame], [PlaybackCommand], etc.) ARE the
 * domain types here — deliberately no parallel `domain.campfire.*` mirror. The usual
 * "wire DTO vs. domain model" split exists to insulate callers from wire-format churn
 * across a *persistence* boundary; campfires have none. They are ephemeral, in-memory,
 * single-process rooms that a server restart wipes entirely (see the `CampfireService`
 * KDoc) — there is no Room table, no sync domain, nothing for a domain model to decouple
 * the caller from. Introducing one here would be ceremony without a payoff.
 *
 * `internal`: consumed only from within `:sharedLogic` (the session controller and its
 * presentation-layer callers), so it never needs to cross the export surface.
 */
internal interface CampfireTransport {
    /** See [com.calypsan.listenup.api.CampfireService.createSession]. */
    suspend fun createSession(
        bookId: String,
        settings: CampfireSettings,
    ): AppResult<CampfireSnapshot>

    /** See [com.calypsan.listenup.api.CampfireService.joinSession]. */
    suspend fun joinSession(sessionId: CampfireId): AppResult<CampfireSnapshot>

    /** See [com.calypsan.listenup.api.CampfireService.leaveSession]. */
    suspend fun leaveSession(sessionId: CampfireId): AppResult<Unit>

    /** See [com.calypsan.listenup.api.CampfireService.endSession]. */
    suspend fun endSession(sessionId: CampfireId): AppResult<Unit>

    /** See [com.calypsan.listenup.api.CampfireService.transferHost]. */
    suspend fun transferHost(
        sessionId: CampfireId,
        toUserId: String,
    ): AppResult<Unit>

    /** See [com.calypsan.listenup.api.CampfireService.setControlMode]. */
    suspend fun setControlMode(
        sessionId: CampfireId,
        mode: CampfireControlMode,
    ): AppResult<Unit>

    /**
     * Passthrough of [com.calypsan.listenup.api.CampfireService.observeSession] — the raw
     * [RpcEvent] envelope, not unwrapped, so the controller can distinguish a server-side
     * guard error from the stream simply ending (see the controller's `Disconnected` state).
     */
    fun observeSession(sessionId: CampfireId): Flow<RpcEvent<CampfireFrame>>

    /**
     * Tears down the underlying connection so the next call — including the next
     * [observeSession] collection — rides a fresh one.
     *
     * Exists for `CampfireSessionController.rejoin()`: under the pinned kotlinx.rpc
     * dev-channel build (0.11.0-grpc-189, see the KRPC-560 note in the version catalog),
     * cancelling a server-streaming flow collection and then re-subscribing on the SAME
     * RPC connection intermittently stalls — the cancelled stream can wedge the
     * connection's next subscription, which then silently delivers nothing (observed on
     * CI in the campfire real-transport E2E). A fresh connection sidesteps the race
     * entirely and is cheap for an explicit user-facing rejoin. Revisit when the stable
     * kotlinx-rpc 0.11.x bump lands.
     */
    suspend fun refreshConnection()

    /** See [com.calypsan.listenup.api.CampfireService.sendCommand]. */
    suspend fun sendCommand(
        sessionId: CampfireId,
        command: PlaybackCommand,
    ): AppResult<Unit>

    /** See [com.calypsan.listenup.api.CampfireService.sendChat]. */
    suspend fun sendChat(
        sessionId: CampfireId,
        text: String,
    ): AppResult<Unit>

    /** See [com.calypsan.listenup.api.CampfireService.sendReaction]. */
    suspend fun sendReaction(
        sessionId: CampfireId,
        emoji: String,
    ): AppResult<Unit>

    /** See [com.calypsan.listenup.api.CampfireService.listOpenSessions]. */
    suspend fun listOpenSessions(): AppResult<List<OpenCampfireSummary>>

    /** See [com.calypsan.listenup.api.CampfireService.listInvitableUsers]. */
    suspend fun listInvitableUsers(bookId: String): AppResult<List<CampfireInvitableUser>>
}
