package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.client.data.remote.AuthApiContract
import com.calypsan.listenup.client.data.remote.AuthResponse
import com.calypsan.listenup.client.data.remote.RegisterResponse
import com.calypsan.listenup.client.data.remote.RegistrationStatusResponse
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.AuthState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidAudioTokenProviderTest {
    @Test
    fun `prepareForPlayback returns without calling refreshToken when cached token is valid`() {
        val accessTokenCalls = AtomicInteger(0)

        val session =
            object : AuthSession {
                override val authState: StateFlow<AuthState> =
                    MutableStateFlow(AuthState.Authenticated(userId = "u1", sessionId = "s1"))

                override suspend fun getAccessToken(): AccessToken? {
                    accessTokenCalls.incrementAndGet()
                    return AccessToken("valid-test-token")
                }

                override suspend fun getRefreshToken(): RefreshToken? = null

                override suspend fun getSessionId(): String? = "s1"

                override suspend fun getUserId(): String? = "u1"

                override suspend fun saveAuthTokens(
                    access: AccessToken,
                    refresh: RefreshToken,
                    sessionId: String,
                    userId: String,
                ) {}

                override suspend fun updateAccessToken(token: AccessToken) {}

                override suspend fun clearAuthTokens() {}

                override suspend fun isAuthenticated(): Boolean = true

                override suspend fun initializeAuthState() {}

                override suspend fun checkServerStatus(): AuthState = AuthState.Authenticated("u1", "s1")

                override suspend fun refreshOpenRegistration() {}

                override suspend fun savePendingRegistration(
                    userId: String,
                    email: String,
                    password: String,
                ) {}

                override suspend fun getPendingRegistration(): Triple<String, String, String>? = null

                override suspend fun clearPendingRegistration() {}
            }

        val authApi =
            object : AuthApiContract {
                override suspend fun setup(
                    email: String,
                    password: String,
                    firstName: String,
                    lastName: String,
                ): AuthResponse = TODO()

                override suspend fun login(
                    email: String,
                    password: String,
                ): AuthResponse = TODO()

                override suspend fun register(
                    email: String,
                    password: String,
                    firstName: String,
                    lastName: String,
                ): RegisterResponse = TODO()

                override suspend fun refresh(refreshToken: RefreshToken): AuthResponse = TODO()

                override suspend fun logout(sessionId: String) = TODO()

                override suspend fun checkRegistrationStatus(userId: String): RegistrationStatusResponse = TODO()
            }

        val scope = CoroutineScope(Job())

        try {
            val provider = AndroidAudioTokenProvider(session, authApi, scope)

            // Let init's refreshToken() complete
            runBlocking { delay(200) }

            val callsAfterInit = accessTokenCalls.get()
            assertTrue(callsAfterInit >= 1, "Init should have called getAccessToken at least once")

            // Act - prepareForPlayback should NOT call refreshToken again
            // because the cached token is valid (set to expire in ~50 minutes)
            runBlocking { provider.prepareForPlayback() }

            // Assert - getAccessToken should not have been called again
            assertEquals(
                callsAfterInit,
                accessTokenCalls.get(),
                "prepareForPlayback should skip refreshToken when cached token is still valid",
            )
        } finally {
            scope.cancel()
        }
    }
}
