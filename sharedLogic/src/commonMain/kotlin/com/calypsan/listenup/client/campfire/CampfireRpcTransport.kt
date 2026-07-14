package com.calypsan.listenup.client.campfire

import com.calypsan.listenup.api.dto.campfire.CampfireControlMode
import com.calypsan.listenup.api.dto.campfire.CampfireFrame
import com.calypsan.listenup.api.dto.campfire.CampfireId
import com.calypsan.listenup.api.dto.campfire.CampfireInvitableUser
import com.calypsan.listenup.api.dto.campfire.CampfireSettings
import com.calypsan.listenup.api.dto.campfire.CampfireSnapshot
import com.calypsan.listenup.api.dto.campfire.OpenCampfireSummary
import com.calypsan.listenup.api.dto.campfire.PlaybackCommand
import com.calypsan.listenup.api.CampfireService
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.client.data.remote.RpcChannel
import kotlinx.coroutines.flow.Flow

/**
 * Production [CampfireTransport]: every unary method dispatches through
 * [RpcChannel.call] — the bounded, self-healing RPC engine that folds a
 * dead-socket recovery into the same [AppResult] boundary a business failure already
 * rides.
 *
 * [observeSession] rides [RpcChannel.stream]: a cold subscription with subscription-time
 * healing whose [RpcEvent] stream is passed through untouched — this transport does no
 * unwrapping, unlike `ImportRepositoryImpl` which discards
 * [RpcEvent.Error]/[RpcEvent.Complete]; the session controller needs to see every
 * variant to drive its own presence/error handling.
 */
internal class CampfireRpcTransport(
    private val channel: RpcChannel<CampfireService>,
) : CampfireTransport {
    override suspend fun createSession(
        bookId: String,
        settings: CampfireSettings,
    ): AppResult<CampfireSnapshot> = channel.call { it.createSession(bookId, settings) }

    override suspend fun joinSession(sessionId: CampfireId): AppResult<CampfireSnapshot> =
        channel.call { it.joinSession(sessionId) }

    override suspend fun leaveSession(sessionId: CampfireId): AppResult<Unit> =
        channel.call {
            it.leaveSession(sessionId)
        }

    override suspend fun endSession(sessionId: CampfireId): AppResult<Unit> =
        channel.call {
            it.endSession(sessionId)
        }

    override suspend fun startSession(sessionId: CampfireId): AppResult<Unit> =
        channel.call { it.startSession(sessionId) }

    override suspend fun updateSettings(
        sessionId: CampfireId,
        settings: CampfireSettings,
    ): AppResult<CampfireSnapshot> = channel.call { it.updateSettings(sessionId, settings) }

    override suspend fun transferHost(
        sessionId: CampfireId,
        toUserId: String,
    ): AppResult<Unit> = channel.call { it.transferHost(sessionId, toUserId) }

    override suspend fun setControlMode(
        sessionId: CampfireId,
        mode: CampfireControlMode,
    ): AppResult<Unit> = channel.call { it.setControlMode(sessionId, mode) }

    override fun observeSession(sessionId: CampfireId): Flow<RpcEvent<CampfireFrame>> =
        channel.stream { it.observeSession(sessionId) }

    /**
     * Drops the cached RPC proxy via [RpcChannel.invalidate] — the dispatch engine
     * rebuilds it (a fresh WebSocket connection) on the next use, the codebase's
     * established self-healing posture. Called by `CampfireSessionController.rejoin()`
     * before re-joining/re-subscribing: under the pinned kotlinx.rpc dev-channel build
     * (0.11.0-grpc-189), a cancelled `observeSession` stream can wedge the SAME
     * connection's next subscription into silently delivering nothing — a fresh
     * connection sidesteps that race, cheaply, for an explicit user-facing rejoin.
     */
    override suspend fun refreshConnection() = channel.invalidate()

    override suspend fun sendCommand(
        sessionId: CampfireId,
        command: PlaybackCommand,
    ): AppResult<Unit> = channel.call { it.sendCommand(sessionId, command) }

    override suspend fun sendChat(
        sessionId: CampfireId,
        text: String,
    ): AppResult<Unit> = channel.call { it.sendChat(sessionId, text) }

    override suspend fun sendReaction(
        sessionId: CampfireId,
        emoji: String,
    ): AppResult<Unit> = channel.call { it.sendReaction(sessionId, emoji) }

    override suspend fun listOpenSessions(): AppResult<List<OpenCampfireSummary>> =
        channel.call(idempotent = true) { it.listOpenSessions() }

    override suspend fun listInvitableUsers(bookId: String): AppResult<List<CampfireInvitableUser>> =
        channel.call(idempotent = true) { it.listInvitableUsers(bookId) }
}
