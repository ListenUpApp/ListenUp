package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.AuthSession as ContractAuthSession
import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.User as ContractUser
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.auth.UserStatus
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.domain.repository.AuthRepository
import com.calypsan.listenup.client.domain.repository.AuthSession
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response

/**
 * Tests for [AudioTokenAuthenticator], the OkHttp Authenticator that refreshes the audio
 * bearer token on 401 and re-issues the failed request with the new token.
 *
 * OkHttp types are pure JVM — no Android runtime is required, so this is a plain Kotest
 * FunSpec without Robolectric. [CachedAudioTokenProvider] is constructed from stub auth
 * dependencies whose refresh behaviour is fully controlled. [CoroutineScope] with
 * [Dispatchers.Unconfined] keeps the init-launched refresh synchronous so token state
 * is stable before each test body runs.
 *
 * Coverage:
 * - Happy path: refresh produces a new token → non-null request with updated Authorization.
 * - Give-up path: refresh produces the same token → null, signalling OkHttp to surface the 401.
 */
class AudioTokenAuthenticatorTest :
    FunSpec({

        test("returns rebuilt request with new bearer token when refresh yields a new token") {
            // FailThenRotateRepository: init's refresh fails → fallbackToStored → getToken()="stored".
            // authenticate's refresh succeeds with "rotated" → previousToken≠newToken → request rebuilt.
            val provider =
                CachedAudioTokenProvider(
                    StubAudioAuthSession(),
                    FailThenRotateRepository(),
                    CoroutineScope(Dispatchers.Unconfined + Job()),
                )
            val request = Request.Builder().url("https://example.com/audio/seg1.ts").build()
            val response =
                Response
                    .Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(401)
                    .message("Unauthorized")
                    .build()

            val result = AudioTokenAuthenticator(provider).authenticate(route = null, response = response)

            result shouldNotBe null
            result!!.header("Authorization") shouldBe "Bearer rotated"
            result.url.toString() shouldBe "https://example.com/audio/seg1.ts"
        }

        test("returns null when token refresh produces the same token as before") {
            // AlwaysSameTokenRepository: every refresh returns "stored". After init the cached
            // token is "stored"; authenticate triggers another refresh that also returns "stored"
            // → newToken == previousToken → give-up path returns null.
            val provider =
                CachedAudioTokenProvider(
                    StubAudioAuthSession(),
                    AlwaysSameTokenRepository(),
                    CoroutineScope(Dispatchers.Unconfined + Job()),
                )
            val request = Request.Builder().url("https://example.com/audio/seg1.ts").build()
            val response =
                Response
                    .Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(401)
                    .message("Unauthorized")
                    .build()

            val result = AudioTokenAuthenticator(provider).authenticate(route = null, response = response)

            result.shouldBeNull()
        }
    })

// ── Stubs ─────────────────────────────────────────────────────────────────────

/**
 * Minimal [AuthSession] stub. [CachedAudioTokenProvider] only calls [getAccessToken]
 * (in the fallback path when refresh fails) and [saveAuthTokens] (on refresh success);
 * all other methods are unreachable in these tests.
 */
private class StubAudioAuthSession : AuthSession {
    override val authState: StateFlow<AuthState> =
        MutableStateFlow(AuthState.Authenticated(UserId("u1"), SessionId("s1")))

    override suspend fun getAccessToken(): AccessToken = AccessToken("stored")

    override suspend fun getRefreshToken(): RefreshToken? = null

    override suspend fun getSessionId(): String? = null

    override suspend fun getUserId(): String? = null

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

    override suspend fun isAuthenticated() = false

    override suspend fun initializeAuthState() = Unit

    override suspend fun checkServerStatus() = authState.value

    override suspend fun refreshOpenRegistration() = Unit

    override suspend fun savePendingRegistration(
        userId: String,
        email: String,
    ) = Unit

    override suspend fun getPendingRegistration(): com.calypsan.listenup.client.domain.repository.PendingRegistration? = null

    override suspend fun clearPendingRegistration() = Unit
}

/**
 * Fails the first [refreshAccessToken] call (so [CachedAudioTokenProvider]'s init-launched
 * refresh falls back to the session-stored token "stored"), then returns "rotated" on all
 * subsequent calls. This makes previousToken="stored" and newToken="rotated" in the happy path.
 */
private class FailThenRotateRepository : AuthRepository {
    private var firstCall = true

    override suspend fun refreshAccessToken(): AppResult<ContractAuthSession> =
        if (firstCall) {
            firstCall = false
            AppResult.Failure(AuthError.InvalidCredentials())
        } else {
            AppResult.Success(stubSession(AccessToken("rotated")))
        }

    override suspend fun login(request: LoginRequest) = TODO()

    override suspend fun register(request: RegisterRequest) = TODO()

    override suspend fun setup(request: RegisterRequest) = TODO()

    override suspend fun logout() = TODO()

    override suspend fun listSessions() = TODO()

    override suspend fun revokeSession(sessionId: SessionId) = TODO()

    override suspend fun logoutAll() = TODO()
}

/**
 * Always returns "stored" from [refreshAccessToken]. After init the cached token is "stored";
 * when [authenticate] triggers a second refresh it also returns "stored" → newToken==previousToken
 * → the give-up branch returns null.
 */
private class AlwaysSameTokenRepository : AuthRepository {
    override suspend fun refreshAccessToken(): AppResult<ContractAuthSession> = AppResult.Success(stubSession(AccessToken("stored")))

    override suspend fun login(request: LoginRequest) = TODO()

    override suspend fun register(request: RegisterRequest) = TODO()

    override suspend fun setup(request: RegisterRequest) = TODO()

    override suspend fun logout() = TODO()

    override suspend fun listSessions() = TODO()

    override suspend fun revokeSession(sessionId: SessionId) = TODO()

    override suspend fun logoutAll() = TODO()
}

private fun stubSession(token: AccessToken): ContractAuthSession =
    ContractAuthSession(
        accessToken = token,
        accessTokenExpiresAt = System.currentTimeMillis() + ONE_HOUR_MS,
        refreshToken = RefreshToken("r"),
        refreshTokenExpiresAt = System.currentTimeMillis() + ONE_HOUR_MS,
        sessionId = SessionId("s1"),
        user =
            ContractUser(
                id = UserId("u1"),
                email = "test@example.com",
                displayName = "Test",
                role = UserRole.MEMBER,
                status = UserStatus.ACTIVE,
                createdAt = 0L,
            ),
    )

private const val ONE_HOUR_MS = 60L * 60L * 1_000L
