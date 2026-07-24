package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.AuthSession as ContractAuthSession
import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.User as ContractUser
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.auth.UserStatus
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.domain.repository.AuthRepository
import com.calypsan.listenup.client.domain.repository.AuthSession
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger

/**
 * Verifies the Android wrapper preserves the [CachedAudioTokenProvider] fast-path:
 * when init's refresh succeeds and the rotated token is good for >2 minutes,
 * `prepareForPlayback` returns without triggering another refresh.
 */
class AndroidAudioTokenProviderTest :
    FunSpec({
        test("prepareForPlayback skips refresh when cached token is still valid") {
            val refreshCalls = AtomicInteger(0)

            val session = FakeAuthSession()
            val repo = FakeAuthRepository(refreshCalls)

            val scope = CoroutineScope(Job())
            try {
                val core = CachedAudioTokenProvider(session, repo, scope)
                val provider = AndroidAudioTokenProvider(core)

                // Let init's refreshToken() complete
                runBlocking { delay(200) }

                val callsAfterInit = refreshCalls.get()
                (callsAfterInit >= 1) shouldBe true

                // prepareForPlayback should NOT call refresh again — the rotated
                // session was issued with an expiry hours in the future.
                runBlocking { provider.prepareForPlayback() }

                refreshCalls.get() shouldBe callsAfterInit
            } finally {
                scope.cancel()
            }
        }
    })

private class FakeAuthRepository(
    private val refreshCalls: AtomicInteger,
) : AuthRepository {
    override suspend fun login(request: LoginRequest): AppResult<ContractAuthSession> = TODO()

    override suspend fun register(request: RegisterRequest): AppResult<RegisterResult> = TODO()

    override suspend fun setup(request: RegisterRequest): AppResult<ContractAuthSession> = TODO()

    override suspend fun logout(): AppResult<Unit> = TODO()

    override suspend fun refreshAccessToken(): AppResult<ContractAuthSession> {
        refreshCalls.incrementAndGet()
        return AppResult.Success(
            ContractAuthSession(
                accessToken = AccessToken("rotated"),
                accessTokenExpiresAt = System.currentTimeMillis() + ONE_HOUR_MS,
                refreshToken = RefreshToken("rotated-refresh"),
                refreshTokenExpiresAt = System.currentTimeMillis() + ONE_HOUR_MS,
                sessionId = SessionId("s1"),
                user =
                    ContractUser(
                        id = UserId("u1"),
                        email = "alice@example.com",
                        displayName = "Alice",
                        role = UserRole.MEMBER,
                        status = UserStatus.ACTIVE,
                        createdAt = 0L,
                    ),
            ),
        )
    }

    override suspend fun listSessions(): AppResult<List<com.calypsan.listenup.api.dto.auth.SessionSummary>> = AppResult.Success(emptyList())

    override suspend fun revokeSession(sessionId: SessionId): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun logoutAll(): AppResult<Unit> = AppResult.Success(Unit)

    companion object {
        private const val ONE_HOUR_MS = 60L * 60L * 1000L
    }
}

private class FakeAuthSession : AuthSession {
    override val authState: StateFlow<AuthState> =
        MutableStateFlow(
            AuthState.Authenticated(userId = UserId("u1"), sessionId = SessionId("s1")),
        )

    override suspend fun getAccessToken(): AccessToken? = AccessToken("stored")

    override suspend fun getRefreshToken(): RefreshToken? = RefreshToken("stored-refresh")

    override suspend fun getSessionId(): String? = "s1"

    override suspend fun getUserId(): String? = "u1"

    override suspend fun currentAuthEpoch(): Long = 0L

    override suspend fun saveAuthTokens(
        access: AccessToken,
        refresh: RefreshToken,
        sessionId: String,
        userId: String,
        ifEpoch: Long?,
    ) = Unit

    override suspend fun updateAccessToken(token: AccessToken) = Unit

    override suspend fun clearAuthTokens() = Unit

    override suspend fun clearSessionCredentials() = Unit

    override suspend fun isAuthenticated(): Boolean = true

    override suspend fun initializeAuthState() = Unit

    override suspend fun checkServerStatus(): AuthState = AuthState.Authenticated(UserId("u1"), SessionId("s1"))

    override suspend fun refreshOpenRegistration() = Unit

    override suspend fun savePendingRegistration(
        userId: String,
        email: String,
    ) = Unit

    override suspend fun getPendingRegistration(): com.calypsan.listenup.client.domain.repository.PendingRegistration? = null

    override suspend fun clearPendingRegistration() = Unit
}
