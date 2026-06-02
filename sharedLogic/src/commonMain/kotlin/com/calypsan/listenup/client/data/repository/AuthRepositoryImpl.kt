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
import com.calypsan.listenup.client.data.remote.AuthRpcFactory
import com.calypsan.listenup.client.domain.repository.AuthRepository
import com.calypsan.listenup.client.domain.repository.AuthSession as ClientAuthSession
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException

private val logger = KotlinLogging.logger {}

/**
 * Thin adapter over [AuthRpcFactory]. Each method picks the right proxy
 * (public vs authed) and forwards. Network/transport failures (unreachable
 * server, deserialization blowups) collapse to `AppResult.Failure(InternalError)`
 * so callers never see a raw exception across the wire.
 *
 * Per kotlinx.coroutines convention, `CancellationException` is re-thrown.
 */
class AuthRepositoryImpl(
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

    override suspend fun refreshAccessToken(): AppResult<AuthSession> {
        val token = authSession.getRefreshToken() ?: return AppResult.Failure(AuthError.SessionExpired())
        return catching("refresh") { rpc.publicService().refreshSession(RefreshRequest(token)) }
    }

    override suspend fun listSessions(): AppResult<List<SessionSummary>> =
        catching("listSessions") { rpc.authedService().listSessions() }

    override suspend fun revokeSession(sessionId: SessionId): AppResult<Unit> =
        catching("revokeSession") { rpc.authedService().revokeSession(sessionId) }

    override suspend fun logoutAll(): AppResult<Unit> =
        catching("logoutAll") { rpc.authedService().logoutAll() }

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
            AppResult.Failure(InternalError())
        }
}
