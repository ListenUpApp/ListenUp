package com.calypsan.listenup.client.test.fake

import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.PendingRegistration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Minimal [AuthSession] test fake backed by a fixed [authState].
 *
 * Most consumers only read [authState] (for the current user id); every other method is a no-op.
 * Pass [userId] to control who the session reports as signed in.
 */
class FakeAuthSession(
    userId: String = "u1",
) : AuthSession {
    override val authState: StateFlow<AuthState> =
        MutableStateFlow(AuthState.Authenticated(UserId(userId), SessionId("session")))

    override suspend fun saveAuthTokens(
        access: AccessToken,
        refresh: RefreshToken,
        sessionId: String,
        userId: String,
    ) = Unit

    override suspend fun getAccessToken(): AccessToken? = null

    override suspend fun getRefreshToken(): RefreshToken? = null

    override suspend fun getSessionId(): String? = null

    override suspend fun getUserId(): String? = null

    override suspend fun updateAccessToken(token: AccessToken) = Unit

    override suspend fun clearAuthTokens() = Unit

    override suspend fun isAuthenticated(): Boolean = true

    override suspend fun initializeAuthState() = Unit

    override suspend fun checkServerStatus(): AuthState = authState.value

    override suspend fun refreshOpenRegistration() = Unit

    override suspend fun savePendingRegistration(
        userId: String,
        email: String,
    ) = Unit

    override suspend fun getPendingRegistration(): PendingRegistration? = null

    override suspend fun clearPendingRegistration() = Unit
}
