package com.calypsan.listenup.client.data.repository

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
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.data.remote.AuthRpcFactory
import com.calypsan.listenup.client.domain.repository.AuthRepository
import com.calypsan.listenup.client.domain.repository.AuthSession as ClientAuthSession
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val logger = KotlinLogging.logger {}

/**
 * Thin adapter over [AuthRpcFactory]. Each method picks the right proxy
 * (public vs authed) and forwards. Thrown transport failures are routed
 * through [com.calypsan.listenup.client.core.error.ErrorMapper] via [Failure],
 * so a transport-level 401 or WS-handshake-401 surfaces as a typed
 * [AuthError.SessionExpired] (driving the session-lapse chain) instead of a
 * generic InternalError, while IO/deserialization failures keep their own
 * typed shapes.
 *
 * Per kotlinx.coroutines convention, `CancellationException` is re-thrown.
 */
internal class AuthRepositoryImpl(
    private val rpc: AuthRpcFactory,
    private val authSession: ClientAuthSession,
) : AuthRepository {
    override suspend fun login(request: LoginRequest): AppResult<AuthSession> =
        catching("login") { rpc.publicService().login(request) }

    override suspend fun register(request: RegisterRequest): AppResult<RegisterResult> =
        catching("register") { rpc.publicService().register(request) }

    override suspend fun setup(request: RegisterRequest): AppResult<AuthSession> =
        catching("setup") { rpc.publicService().setupRoot(request) }

    override suspend fun logout(): AppResult<Unit> = catching("logout") { rpc.authedService().logout() }

    private val refreshMutex = Mutex()
    private var inFlightRefresh: CompletableDeferred<AppResult<AuthSession>>? = null

    /**
     * Single-flight token refresh. The refresh token rotates on every use, so two
     * concurrent refreshes (e.g. the bearer plugin's on-401 path racing the
     * playback token provider's proactive loop) would each present the same token —
     * the server's replay detection reads the second as a stolen token and revokes
     * the whole session family, force-logging-out the user mid-listen. Coalescing
     * concurrent callers onto one in-flight refresh keeps exactly one rotation.
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
                    catching("refresh") { rpc.publicService().refreshSession(RefreshRequest(token)) }
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

    override suspend fun listSessions(): AppResult<List<SessionSummary>> =
        catching("listSessions") { rpc.authedService().listSessions() }

    override suspend fun revokeSession(sessionId: SessionId): AppResult<Unit> =
        catching("revokeSession") { rpc.authedService().revokeSession(sessionId) }

    override suspend fun logoutAll(): AppResult<Unit> = catching("logoutAll") { rpc.authedService().logoutAll() }

    private suspend inline fun <T> catching(
        op: String,
        block: () -> AppResult<T>,
    ): AppResult<T> =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "auth $op failed at the transport boundary" }
            Failure(e)
        }
}
