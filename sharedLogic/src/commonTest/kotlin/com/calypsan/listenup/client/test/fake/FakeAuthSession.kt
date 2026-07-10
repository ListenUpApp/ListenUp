package com.calypsan.listenup.client.test.fake

import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.PendingRegistration
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Minimal [AuthSession] test fake.
 *
 * Supports two independent control points so tests can simulate the startup race where
 * [authState] is still `Initializing` while [getUserId] already has the
 * persisted id from secure storage:
 *
 * - [authState]: the reactive state observable. Defaults to [AuthState.Authenticated] so
 *   existing tests that only read `authState` are unaffected.
 * - [getUserIdResult]: what [getUserId] returns (the persisted id, `null` when signed out).
 *   Defaults to the non-null [userId] string for backwards compatibility.
 *
 * Use the named-parameter overload for the race scenario:
 * ```kotlin
 * FakeAuthSession(authState = AuthState.Initializing, userId = "user-123")
 * FakeAuthSession(authState = AuthState.Initializing, userId = null) // signed-out
 * ```
 */
class FakeAuthSession(
    userId: String? = "u1",
    authState: AuthState = AuthState.Authenticated(UserId(userId ?: "u1"), SessionId("session")),
    private val onClearAuthTokens: () -> Unit = {},
    private val onClearSessionCredentials: () -> Unit = {},
) : AuthSession {
    override val authState: MutableStateFlow<AuthState> = MutableStateFlow(authState)

    private val getUserIdResult: String? = userId

    override suspend fun saveAuthTokens(
        access: AccessToken,
        refresh: RefreshToken,
        sessionId: String,
        userId: String,
    ) = Unit

    override suspend fun getAccessToken(): AccessToken? = null

    override suspend fun getRefreshToken(): RefreshToken? = null

    override suspend fun getSessionId(): String? = null

    override suspend fun getUserId(): String? = getUserIdResult

    override suspend fun updateAccessToken(token: AccessToken) = Unit

    override suspend fun clearAuthTokens() = onClearAuthTokens()

    override suspend fun clearSessionCredentials() = onClearSessionCredentials()

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
