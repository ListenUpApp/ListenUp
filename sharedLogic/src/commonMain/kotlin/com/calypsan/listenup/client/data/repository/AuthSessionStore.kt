package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.core.Failure
import com.calypsan.listenup.core.SecureStorage
import com.calypsan.listenup.core.Success
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import com.calypsan.listenup.client.domain.repository.PendingRegistration
import com.calypsan.listenup.client.domain.repository.ServerConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.TimeSource
import com.calypsan.listenup.client.domain.model.AuthState as DomainAuthState

private val logger = KotlinLogging.logger {}

/**
 * Owns the client-side authentication slice: token storage, the reactive
 * `authState` flow, server-status checks that drive that flow, and the
 * pending-registration sidecar storage.
 *
 * Extracted from `SettingsRepositoryImpl` so the latter can focus on user
 * preferences and server-URL plumbing.
 */
class AuthSessionStore(
    private val secureStorage: SecureStorage,
    private val serverConfig: ServerConfig,
    private val instanceRepository: InstanceRepository,
) : AuthSession {
    private val _authState = MutableStateFlow<DomainAuthState>(DomainAuthState.Initializing)
    override val authState: StateFlow<DomainAuthState> = _authState.asStateFlow()

    override suspend fun saveAuthTokens(
        access: AccessToken,
        refresh: RefreshToken,
        sessionId: String,
        userId: String,
    ) {
        secureStorage.save(KEY_ACCESS_TOKEN, access.value)
        secureStorage.save(KEY_REFRESH_TOKEN, refresh.value)
        secureStorage.save(KEY_SESSION_ID, sessionId)
        secureStorage.save(KEY_USER_ID, userId)

        _authState.value = DomainAuthState.Authenticated(UserId(userId), SessionId(sessionId))
    }

    override suspend fun getAccessToken(): AccessToken? = secureStorage.read(KEY_ACCESS_TOKEN)?.let { AccessToken(it) }

    override suspend fun getRefreshToken(): RefreshToken? =
        secureStorage.read(KEY_REFRESH_TOKEN)?.let { RefreshToken(it) }

    override suspend fun getSessionId(): String? = secureStorage.read(KEY_SESSION_ID)

    override suspend fun getUserId(): String? = secureStorage.read(KEY_USER_ID)

    override suspend fun updateAccessToken(token: AccessToken) {
        secureStorage.save(KEY_ACCESS_TOKEN, token.value)
    }

    /**
     * Soft logout: drops the four token keys and routes to NeedsLogin without
     * making a network call. Server reachability is implied by the 401 that
     * triggered this; another HTTP call would fight with the auth failure.
     */
    override suspend fun clearAuthTokens() {
        secureStorage.delete(KEY_ACCESS_TOKEN)
        secureStorage.delete(KEY_REFRESH_TOKEN)
        secureStorage.delete(KEY_SESSION_ID)
        secureStorage.delete(KEY_USER_ID)

        _authState.value = DomainAuthState.NeedsLogin(openRegistration = getCachedOpenRegistration())
    }

    override suspend fun isAuthenticated(): Boolean = getAccessToken() != null

    /**
     * Recompute auth state from currently-persisted data. Offline-first — no
     * network call; invalid tokens surface later as 401s and trigger re-auth.
     */
    override suspend fun initializeAuthState() {
        _authState.value = deriveAuthState()
    }

    private suspend fun deriveAuthState(): DomainAuthState {
        val serverUrl = serverConfig.getServerUrl()
        if (serverUrl == null) {
            return DomainAuthState.NeedsServerUrl
        }

        val hasToken = getAccessToken() != null
        val userId = getUserId()
        val sessionId = getSessionId()

        if (hasToken && userId != null && sessionId != null) {
            return DomainAuthState.Authenticated(UserId(userId), SessionId(sessionId))
        }

        // Token present without userId/sessionId means a partial save or storage
        // corruption — clear and require fresh login rather than render placeholders.
        if (hasToken) {
            clearAuthTokens()
            return DomainAuthState.NeedsLogin(openRegistration = getCachedOpenRegistration())
        }

        val pendingRegistration = getPendingRegistration()
        if (pendingRegistration != null) {
            return DomainAuthState.PendingApproval(
                userId = UserId(pendingRegistration.userId),
                email = pendingRegistration.email,
            )
        }

        return DomainAuthState.NeedsLogin(openRegistration = getCachedOpenRegistration())
    }

    /**
     * Hit the server's instance endpoint to learn whether setup is required.
     * Caches `openRegistration` for offline-first state derivation. On network
     * failure we stay in NeedsLogin — never blow away the URL automatically.
     */
    override suspend fun checkServerStatus(): DomainAuthState {
        logger.info { "checkServerStatus: Starting" }
        val startMark = TimeSource.Monotonic.markNow()
        _authState.value = DomainAuthState.CheckingServer

        return when (val result = instanceRepository.getInstance(forceRefresh = true)) {
            is Success -> {
                logger.info { "checkServerStatus: getInstance succeeded (${startMark.elapsedNow()})" }
                secureStorage.save(KEY_OPEN_REGISTRATION, result.data.openRegistration.toString())

                val newState =
                    if (result.data.setupRequired) {
                        DomainAuthState.NeedsSetup
                    } else {
                        DomainAuthState.NeedsLogin(openRegistration = result.data.openRegistration)
                    }
                _authState.value = newState
                newState
            }

            is Failure -> {
                logger.info { "checkServerStatus: getInstance failed (${startMark.elapsedNow()}): ${result.message}" }
                val cachedOpenRegistration = getCachedOpenRegistration()
                _authState.value = DomainAuthState.NeedsLogin(openRegistration = cachedOpenRegistration)
                DomainAuthState.NeedsLogin(openRegistration = cachedOpenRegistration)
            }
        }
    }

    private suspend fun getCachedOpenRegistration(): Boolean =
        secureStorage.read(KEY_OPEN_REGISTRATION)?.toBooleanStrictOrNull() ?: false

    override suspend fun refreshOpenRegistration() {
        val currentState = _authState.value
        if (currentState !is DomainAuthState.NeedsLogin) return

        when (val result = instanceRepository.getInstance(forceRefresh = true)) {
            is Success -> {
                secureStorage.save(KEY_OPEN_REGISTRATION, result.data.openRegistration.toString())
                if (_authState.value is DomainAuthState.NeedsLogin) {
                    _authState.value = DomainAuthState.NeedsLogin(openRegistration = result.data.openRegistration)
                }
            }

            is Failure -> {
                // Silently fail — keep the cached value.
            }
        }
    }

    override suspend fun savePendingRegistration(
        userId: String,
        email: String,
    ) {
        secureStorage.save(KEY_PENDING_USER_ID, userId)
        secureStorage.save(KEY_PENDING_EMAIL, email)

        _authState.value =
            DomainAuthState.PendingApproval(
                userId = UserId(userId),
                email = email,
            )
    }

    override suspend fun getPendingRegistration(): PendingRegistration? {
        val userId = secureStorage.read(KEY_PENDING_USER_ID) ?: return null
        val email = secureStorage.read(KEY_PENDING_EMAIL) ?: return null
        return PendingRegistration(userId, email)
    }

    override suspend fun clearPendingRegistration() {
        secureStorage.delete(KEY_PENDING_USER_ID)
        secureStorage.delete(KEY_PENDING_EMAIL)
    }

    private companion object {
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_SESSION_ID = "session_id"
        const val KEY_USER_ID = "user_id"
        const val KEY_OPEN_REGISTRATION = "open_registration"
        const val KEY_PENDING_USER_ID = "pending_user_id"
        const val KEY_PENDING_EMAIL = "pending_email"
    }
}
