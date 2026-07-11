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
import com.calypsan.listenup.client.data.remote.CampfireRpcFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * Production [CampfireTransport]: every unary method dispatches through
 * [CampfireRpcFactory.callResult] — the bounded, self-healing RPC engine that folds a
 * dead-socket recovery into the same [AppResult] boundary a business failure already
 * rides.
 *
 * [observeSession] can't ride `callResult` — that boundary is shaped for a single
 * request/response, not a long-lived server-pushed [Flow]. Instead it mirrors the
 * `ImportRepositoryImpl.observeProgress` precedent: wrap in a cold `flow { }` builder so
 * the [CampfireRpcFactory.get] proxy is acquired at *collection* time (not at call time),
 * keeping the flow truly cold, then [emitAll] the service's raw [RpcEvent] stream
 * untouched — this transport does no unwrapping, unlike `ImportRepositoryImpl` which
 * discards [RpcEvent.Error]/[RpcEvent.Complete]; the session controller needs to see
 * every variant to drive its own presence/error handling.
 */
internal class CampfireRpcTransport(
    private val rpcFactory: CampfireRpcFactory,
) : CampfireTransport {
    override suspend fun createSession(
        bookId: String,
        settings: CampfireSettings,
    ): AppResult<CampfireSnapshot> = rpcFactory.callResult { it.createSession(bookId, settings) }

    override suspend fun joinSession(sessionId: CampfireId): AppResult<CampfireSnapshot> =
        rpcFactory.callResult { it.joinSession(sessionId) }

    override suspend fun leaveSession(sessionId: CampfireId): AppResult<Unit> =
        rpcFactory.callResult {
            it.leaveSession(sessionId)
        }

    override suspend fun endSession(sessionId: CampfireId): AppResult<Unit> =
        rpcFactory.callResult {
            it.endSession(sessionId)
        }

    override suspend fun startSession(sessionId: CampfireId): AppResult<Unit> =
        rpcFactory.callResult { it.startSession(sessionId) }

    override suspend fun updateSettings(
        sessionId: CampfireId,
        settings: CampfireSettings,
    ): AppResult<CampfireSnapshot> = rpcFactory.callResult { it.updateSettings(sessionId, settings) }

    override suspend fun transferHost(
        sessionId: CampfireId,
        toUserId: String,
    ): AppResult<Unit> = rpcFactory.callResult { it.transferHost(sessionId, toUserId) }

    override suspend fun setControlMode(
        sessionId: CampfireId,
        mode: CampfireControlMode,
    ): AppResult<Unit> = rpcFactory.callResult { it.setControlMode(sessionId, mode) }

    override fun observeSession(sessionId: CampfireId): Flow<RpcEvent<CampfireFrame>> =
        flow {
            // Acquire the proxy at collection time so the cold flow stays truly cold.
            emitAll(rpcFactory.get().observeSession(sessionId))
        }

    /**
     * Drops the cached RPC proxy via [CampfireRpcFactory.invalidate] — `RpcProxyCache`
     * rebuilds it (a fresh WebSocket connection) on the next use, the codebase's
     * established self-healing posture. Called by `CampfireSessionController.rejoin()`
     * before re-joining/re-subscribing: under the pinned kotlinx.rpc dev-channel build
     * (0.11.0-grpc-189), a cancelled `observeSession` stream can wedge the SAME
     * connection's next subscription into silently delivering nothing — a fresh
     * connection sidesteps that race, cheaply, for an explicit user-facing rejoin.
     */
    override suspend fun refreshConnection() = rpcFactory.invalidate()

    override suspend fun sendCommand(
        sessionId: CampfireId,
        command: PlaybackCommand,
    ): AppResult<Unit> = rpcFactory.callResult { it.sendCommand(sessionId, command) }

    override suspend fun sendChat(
        sessionId: CampfireId,
        text: String,
    ): AppResult<Unit> = rpcFactory.callResult { it.sendChat(sessionId, text) }

    override suspend fun sendReaction(
        sessionId: CampfireId,
        emoji: String,
    ): AppResult<Unit> = rpcFactory.callResult { it.sendReaction(sessionId, emoji) }

    override suspend fun listOpenSessions(): AppResult<List<OpenCampfireSummary>> =
        rpcFactory.callResult { it.listOpenSessions() }

    override suspend fun listInvitableUsers(bookId: String): AppResult<List<CampfireInvitableUser>> =
        rpcFactory.callResult { it.listInvitableUsers(bookId) }
}
