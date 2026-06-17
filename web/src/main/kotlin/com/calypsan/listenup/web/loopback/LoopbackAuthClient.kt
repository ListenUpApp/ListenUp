package com.calypsan.listenup.web.loopback

import com.calypsan.listenup.api.dto.ServerInfo
import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.RefreshRequest
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.dto.auth.RegistrationStatusEvent
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.SessionSummary
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.result.AppResult

/**
 * The BFF's typed view of the server's auth REST surface, reached over loopback HTTP.
 * `:web` depends only on `:contract`; this interface is the single seam through which
 * web routes touch the domain. Injected so flow tests can supply a fake.
 */
interface LoopbackAuthClient {
    /** `GET /api/v1/instance` — bare [ServerInfo] body. */
    suspend fun serverInfo(): AppResult<ServerInfo>

    suspend fun login(request: LoginRequest): AppResult<AuthSession>

    suspend fun setup(request: RegisterRequest): AppResult<AuthSession>

    suspend fun register(request: RegisterRequest): AppResult<RegisterResult>

    suspend fun refresh(request: RefreshRequest): AppResult<AuthSession>

    suspend fun logout(accessToken: AccessToken): AppResult<Unit>

    suspend fun listSessions(accessToken: AccessToken): AppResult<List<SessionSummary>>

    suspend fun revokeSession(accessToken: AccessToken, id: SessionId): AppResult<Unit>

    /** `GET /api/v1/auth/registration-status/{userId}` — bare [RegistrationStatusEvent] body. */
    suspend fun registrationStatus(userId: UserId): AppResult<RegistrationStatusEvent>
}
