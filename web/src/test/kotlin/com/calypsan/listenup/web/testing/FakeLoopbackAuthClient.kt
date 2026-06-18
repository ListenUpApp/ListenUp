package com.calypsan.listenup.web.testing

import com.calypsan.listenup.api.dto.ServerInfo
import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.RefreshRequest
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.dto.auth.RegistrationStatusEvent
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.SessionSummary
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.web.loopback.LoopbackAuthClient
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger

/**
 * Canned-response [LoopbackAuthClient] for fast in-memory web-route tests. Each method
 * returns its mutable `*Result`; refresh additionally counts calls and can [delay] to
 * force concurrency in the single-flight test.
 */
class FakeLoopbackAuthClient : LoopbackAuthClient {
    var serverInfoResult: AppResult<ServerInfo> =
        AppResult.Success(
            ServerInfo(
                name = "ListenUp",
                version = "0.0.1",
                apiVersion = "v1",
                setupRequired = false,
                registrationPolicy = RegistrationPolicy.OPEN,
                instanceId = "test-instance",
            ),
        )
    var loginResult: AppResult<AuthSession> = AppResult.Failure(InternalError(debugInfo = "unset"))
    var setupResult: AppResult<AuthSession> = AppResult.Failure(InternalError(debugInfo = "unset"))
    var registerResult: AppResult<RegisterResult> = AppResult.Failure(InternalError(debugInfo = "unset"))
    var refreshResult: AppResult<AuthSession> = AppResult.Failure(InternalError(debugInfo = "unset"))
    var logoutResult: AppResult<Unit> = AppResult.Success(Unit)
    var listSessionsResult: AppResult<List<SessionSummary>> = AppResult.Success(emptyList())
    var revokeResult: AppResult<Unit> = AppResult.Success(Unit)
    var registrationStatusResult: AppResult<RegistrationStatusEvent> =
        AppResult.Success(RegistrationStatusEvent(status = "pending"))

    var refreshDelayMs: Long = 0L
    private val refreshCounter = AtomicInteger(0)
    val refreshCalls: Int get() = refreshCounter.get()

    override suspend fun serverInfo() = serverInfoResult

    override suspend fun login(request: LoginRequest) = loginResult

    override suspend fun setup(request: RegisterRequest) = setupResult

    override suspend fun register(request: RegisterRequest) = registerResult

    override suspend fun refresh(request: RefreshRequest): AppResult<AuthSession> {
        refreshCounter.incrementAndGet()
        if (refreshDelayMs > 0) delay(refreshDelayMs)
        return refreshResult
    }

    override suspend fun logout(accessToken: AccessToken) = logoutResult

    override suspend fun listSessions(accessToken: AccessToken) = listSessionsResult

    override suspend fun revokeSession(accessToken: AccessToken, id: SessionId) = revokeResult

    override suspend fun registrationStatus(userId: UserId) = registrationStatusResult
}
