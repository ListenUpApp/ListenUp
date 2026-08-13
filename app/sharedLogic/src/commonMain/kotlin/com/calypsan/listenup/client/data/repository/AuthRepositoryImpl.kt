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
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private val logger = KotlinLogging.logger {}

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
 *
 * [scope] is where [refreshAccessToken]'s actual refresh runs — deliberately NOT the calling
 * coroutine. See that method's KDoc for why: a caller bounding its own wait with
 * `withTimeoutOrNull` (every current caller does — [com.calypsan.listenup.client.data.remote.RpcAuthRecoveryImpl],
 * [com.calypsan.listenup.client.playback.CachedAudioTokenProvider]'s forced and proactive refreshes
 * — and any future one) must be able to give up without aborting a rotation another caller, or the
 * next call, is counting on. The invariant belongs here, at the single-flight itself, not
 * re-implemented per call site — every caller inherits cancellation-safety for free and needs only
 * its own budget.
 */
internal class AuthRepositoryImpl(
    private val authPublicChannel: RpcChannel<AuthServicePublic>,
    private val authedChannel: RpcChannel<AuthServiceAuthed>,
    private val authSession: ClientAuthSession,
    private val scope: CoroutineScope,
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
     * The actual refresh runs on [scope] — see the class KDoc — via [performRefresh], launched
     * ONLY by the leader (the caller that wins [inFlightRefresh]). Every caller, leader included,
     * merely `.await()`s the shared [CompletableDeferred]; a [kotlinx.coroutines.Deferred] parented
     * to an unrelated scope is safe to abandon (e.g. a caller's own `withTimeoutOrNull` giving up)
     * without cancelling the underlying job. Before this, the leader's own work ran on WHICHEVER
     * coroutine happened to win leadership — so a caller bounding its own wait would cancel the
     * refresh itself, not merely stop waiting for it. On a network merely slower than that caller's
     * budget, EVERY refresh attempt would be cancelled before completing: a transient slowness became
     * a PERMANENT inability to refresh, strictly worse than an unbounded wait.
     *
     * The refresh RPC rides [authPublicChannel] (recovery = None): the refresh call is
     * itself what a 401 recovery invokes, so it must never be able to trigger one.
     *
     * Persistence (C1) is done inside [performRefresh], inside the single-flight and BEFORE
     * leadership is released, so no later caller can present a stale refresh token after rotation.
     * The auth epoch captured at the start guards it (C8): a logout that intervened makes the
     * persist a no-op via [AuthSession.saveAuthTokens].
     */
    override suspend fun refreshAccessToken(): AppResult<AuthSession> {
        val pending = CompletableDeferred<AppResult<AuthSession>>()
        val deferred =
            refreshMutex.withLock { inFlightRefresh ?: pending.also { inFlightRefresh = it } }
        if (deferred !== pending) return deferred.await()

        // We are the leader. Launch the refresh on `scope` — see the class KDoc for why this must
        // NOT run on the calling coroutine — then await it like any other caller would.
        scope.launch {
            try {
                deferred.complete(performRefresh())
            } catch (e: CancellationException) {
                // `scope` itself was cancelled (app shutdown/logout sweep) mid-refresh. Complete
                // with a plain VALUE, not exceptionally — completing exceptionally would re-throw
                // this CancellationException from every waiter's own `.await()`, incorrectly
                // cancelling their independent callers. Still re-thrown so `scope`'s own job
                // completes as cancelled, per kotlinx.coroutines convention.
                deferred.complete(AppResult.Failure(InternalError()))
                throw e
            } catch (e: Throwable) {
                // The leader MUST complete its deferred on ANY throw (e.g. a getRefreshToken()
                // secure-storage read failure) — otherwise every waiter (this launch's own caller,
                // and anyone who coalesced onto it) awaits forever. NOT re-thrown, deliberately: this
                // runs on `scope`, independent of any caller, so re-throwing here would surface as an
                // uncaught failure of THAT scope rather than reach whoever is actually waiting — who
                // already receives the typed Failure via the deferred. Logged so the fault is still
                // diagnosable.
                logger.warn(e) { "Token refresh failed" }
                deferred.complete(AppResult.Failure(InternalError()))
            } finally {
                // NonCancellable: this cleanup usually runs BECAUSE `scope` was cancelled — a bare
                // `withLock` would then throw and leave `inFlightRefresh` wedged forever, permanently
                // stranding every future refresh behind a dead entry (mirrors SyncEngine's identical
                // guard on its own cancellation-path cleanup).
                withContext(NonCancellable) {
                    refreshMutex.withLock { if (inFlightRefresh === deferred) inFlightRefresh = null }
                }
            }
        }
        return deferred.await()
    }

    /** The actual refresh + persist work. Caller (the leader launch above) owns [deferred]. */
    private suspend fun performRefresh(): AppResult<AuthSession> {
        val epoch = authSession.currentAuthEpoch()
        val token = authSession.getRefreshToken()
        val result =
            if (token == null) {
                AppResult.Failure(AuthError.SessionExpired())
            } else {
                authPublicChannel.call { it.refreshSession(RefreshRequest(token)) }
            }
        if (result is AppResult.Success) {
            val session = result.data
            authSession.saveAuthTokens(
                access = session.accessToken,
                refresh = session.refreshToken,
                sessionId = session.sessionId.value,
                userId = session.user.id.value,
                ifEpoch = epoch,
            )
        }
        return result
    }

    override suspend fun listSessions(): AppResult<List<SessionSummary>> =
        authedChannel.call(idempotent = true) {
            it.listSessions()
        }

    override suspend fun revokeSession(sessionId: SessionId): AppResult<Unit> =
        authedChannel.call { it.revokeSession(sessionId) }

    override suspend fun logoutAll(): AppResult<Unit> = authedChannel.call { it.logoutAll() }
}
