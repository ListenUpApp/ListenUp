package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.AuthServiceAuthed
import com.calypsan.listenup.api.AuthServicePublic
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.RefreshRequest
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.SessionSummary
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.domain.repository.AuthRepository
import com.calypsan.listenup.client.domain.repository.AuthSession as ClientAuthSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Thin adapter over the split auth RPC surface, dispatching through two [RpcChannel]s.
 *
 * The channel split is the recursion firewall, not an organizational nicety: the pre-auth
 * handshake calls (login, register, setupRoot, and — critically — refreshSession) ride
 * [authPublicChannel], an anonymous `RpcPolicy.Public` channel whose recovery is `None`. The
 * bearer-gated session calls (logout, listSessions, revokeSession, logoutAll) ride
 * [authedChannel], which self-heals a handshake 401 with one refresh + retry. Because the refresh
 * primitive itself rides the Public (never-recover) channel, a 401 during refresh can never loop
 * back into another refresh.
 *
 * Each channel folds transport faults into a typed [AppResult.Failure] (a WS-handshake 401 surfaces
 * as [AuthError.SessionExpired], driving the session-lapse chain) and re-raises
 * `CancellationException` per kotlinx.coroutines convention.
 */
internal class AuthRepositoryImpl(
    private val authPublicChannel: RpcChannel<AuthServicePublic>,
    private val authedChannel: RpcChannel<AuthServiceAuthed>,
    private val authSession: ClientAuthSession,
) : AuthRepository {
    override suspend fun login(request: LoginRequest): AppResult<AuthSession> =
        authPublicChannel.call { it.login(request) }

    override suspend fun register(request: RegisterRequest): AppResult<RegisterResult> =
        authPublicChannel.call { it.register(request) }

    override suspend fun setup(request: RegisterRequest): AppResult<AuthSession> =
        authPublicChannel.call { it.setupRoot(request) }

    override suspend fun logout(): AppResult<Unit> = authedChannel.call { it.logout() }

    private val refreshMutex = Mutex()
    private var inFlightRefresh: CompletableDeferred<AppResult<AuthSession>>? = null

    /**
     * Single-flight token refresh. The refresh token rotates on every use, so two
     * concurrent refreshes (e.g. the bearer plugin's on-401 path racing the
     * playback token provider's proactive loop) would each present the same token —
     * the server's replay detection reads the second as a stolen token and revokes
     * the whole session family, force-logging-out the user mid-listen. Coalescing
     * concurrent callers onto one in-flight refresh keeps exactly one rotation.
     *
     * The refresh RPC rides [authPublicChannel] (recovery = None): the refresh call is
     * itself what a 401 recovery invokes, so it must never be able to trigger one.
     */
    override suspend fun refreshAccessToken(): AppResult<AuthSession> {
        val leader = CompletableDeferred<AppResult<AuthSession>>()
        val existing =
            refreshMutex.withLock { inFlightRefresh ?: leader.also { inFlightRefresh = it } }
        if (existing !== leader) return existing.await()

        return try {
            val token = authSession.getRefreshToken()
            val result =
                if (token == null) {
                    AppResult.Failure(AuthError.SessionExpired())
                } else {
                    authPublicChannel.call { it.refreshSession(RefreshRequest(token)) }
                }
            leader.complete(result)
            result
        } catch (e: CancellationException) {
            // Wake followers with a transient failure rather than cancelling their
            // (independent) coroutines; they retry on their next trigger.
            leader.complete(AppResult.Failure(InternalError()))
            throw e
        } finally {
            refreshMutex.withLock { if (inFlightRefresh === leader) inFlightRefresh = null }
        }
    }

    override suspend fun listSessions(): AppResult<List<SessionSummary>> = authedChannel.call { it.listSessions() }

    override suspend fun revokeSession(sessionId: SessionId): AppResult<Unit> =
        authedChannel.call { it.revokeSession(sessionId) }

    override suspend fun logoutAll(): AppResult<Unit> = authedChannel.call { it.logoutAll() }
}
