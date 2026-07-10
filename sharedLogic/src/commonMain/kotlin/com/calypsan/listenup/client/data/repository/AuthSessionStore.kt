package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.core.SecureStorage
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import com.calypsan.listenup.client.domain.repository.PendingRegistration
import com.calypsan.listenup.client.domain.repository.RegistrationPolicyStream
import com.calypsan.listenup.client.domain.repository.ServerConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
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
internal class AuthSessionStore(
    private val secureStorage: SecureStorage,
    private val serverConfig: ServerConfig,
    private val instanceRepository: InstanceRepository,
    // Lazy to break the Koin construction cycle: the policy stream pulls ApiClientFactory, whose
    // auth-refresh path resolves back to this AuthSession. The stream is only touched later, once
    // the login screen shows, by which point the graph is fully built. Mirrors SettingsRepositoryImpl's
    // Lazy<AuthSession>.
    policyStream: Lazy<RegistrationPolicyStream>,
    private val scope: CoroutineScope,
) : AuthSession {
    private val policyStream by policyStream
    override val authState: StateFlow<DomainAuthState>
        field = MutableStateFlow<DomainAuthState>(DomainAuthState.Initializing)

    init {
        observeRegistrationPolicy()
    }

    /**
     * Keeps `openRegistration` live while the login screen is showing: subscribes to the server's
     * registration-policy SSE only in [DomainAuthState.NeedsLogin] and flips the Sign Up affordance
     * the instant an admin closes (or reopens) registration — no relaunch, no pull-to-refresh.
     *
     * Scoped to NeedsLogin via [flatMapLatest] over a `NeedsLogin?`-boolean so our own state writes
     * don't churn the subscription. A dropped connection retries with backoff (still never-stranded:
     * [refreshOpenRegistration]'s one-shot fetch and the cached value remain the fallback).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeRegistrationPolicy() {
        scope.launch {
            authState
                .map { it is DomainAuthState.NeedsLogin }
                .distinctUntilChanged()
                .flatMapLatest { onLoginScreen ->
                    if (onLoginScreen) resilientPolicyStream() else emptyFlow()
                }.collect { policy ->
                    applyOpenRegistration(policy != RegistrationPolicy.CLOSED)
                }
        }
    }

    /** The policy SSE, reconnecting with a fixed backoff on any non-cancellation failure. */
    private fun resilientPolicyStream(): Flow<RegistrationPolicy> =
        policyStream.streamPolicy().retryWhen { cause, _ ->
            if (cause is CancellationException) throw cause
            logger.warn(cause) { "registration-policy stream dropped; reconnecting" }
            delay(POLICY_STREAM_RETRY_MILLIS)
            true
        }

    /** Persists the cached flag and, when still on the login screen, flips the live auth state. */
    private suspend fun applyOpenRegistration(open: Boolean) {
        secureStorage.save(KEY_OPEN_REGISTRATION, open.toString())
        val current = authState.value
        if (current is DomainAuthState.NeedsLogin && current.openRegistration != open) {
            authState.value = DomainAuthState.NeedsLogin(openRegistration = open)
        }
    }

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

        authState.value = DomainAuthState.Authenticated(UserId(userId), SessionId(sessionId))
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
     * Full credential wipe (tokens AND user id) routing to NeedsLogin, without a network call.
     * This is the deliberate-wall path: explicit sign-out, account deletion, and server-instance
     * change (a different server ⇒ broken data provenance). Same-server session expiry goes
     * through [clearSessionCredentials] instead, which keeps the user id and never walls.
     */
    override suspend fun clearAuthTokens() {
        secureStorage.delete(KEY_ACCESS_TOKEN)
        secureStorage.delete(KEY_REFRESH_TOKEN)
        secureStorage.delete(KEY_SESSION_ID)
        secureStorage.delete(KEY_USER_ID)

        authState.value = DomainAuthState.NeedsLogin(openRegistration = getCachedOpenRegistration())
    }

    override suspend fun clearSessionCredentials() {
        val userId = getUserId()
        if (userId == null) {
            // No persisted identity to lapse into (fresh install / already signed out) —
            // fall back to the full clear rather than invent a SessionLapsed without a user.
            clearAuthTokens()
            return
        }
        secureStorage.delete(KEY_ACCESS_TOKEN)
        secureStorage.delete(KEY_REFRESH_TOKEN)
        secureStorage.delete(KEY_SESSION_ID)

        authState.value = DomainAuthState.SessionLapsed(UserId(userId))
    }

    override suspend fun isAuthenticated(): Boolean = getAccessToken() != null

    /**
     * Recompute auth state from currently-persisted data. Offline-first — no
     * network call; invalid tokens surface later as 401s and trigger re-auth.
     */
    override suspend fun initializeAuthState() {
        authState.value = deriveAuthState()
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

        // Persisted identity without an access token = a lapsed session: the user was signed in
        // on this device and their local data is intact. Shell stays mounted; sync parks; the
        // banner offers sign-in (M2/M3). A fresh install has no userId and falls through to the
        // login screen — the one locked cold-start exception.
        if (userId != null) {
            return DomainAuthState.SessionLapsed(UserId(userId))
        }

        val pendingRegistration = getPendingRegistration()
        if (pendingRegistration != null) {
            return DomainAuthState.PendingApproval(
                userId = UserId(pendingRegistration.userId),
                email = pendingRegistration.email,
            )
        }

        // Honour the last-known setupRequired so a relaunch on a server that still has no admin routes
        // to setup, not to login — offline, from the value cached by [checkServerStatus]. A stale "true"
        // self-corrects: SetupViewModel re-runs checkServerStatus on entry.
        return if (getCachedSetupRequired()) {
            DomainAuthState.NeedsSetup
        } else {
            DomainAuthState.NeedsLogin(openRegistration = getCachedOpenRegistration())
        }
    }

    /**
     * Hit the server's instance endpoint to learn whether setup is required.
     * Caches `setupRequired` + `openRegistration` for offline-first state derivation. On network
     * failure we stay in NeedsLogin — never blow away the URL automatically.
     */
    override suspend fun checkServerStatus(): DomainAuthState {
        logger.info { "checkServerStatus: Starting" }
        val startMark = TimeSource.Monotonic.markNow()
        authState.value = DomainAuthState.CheckingServer

        return when (val result = instanceRepository.getServerInfo(forceRefresh = true)) {
            is AppResult.Success -> {
                logger.info { "checkServerStatus: getServerInfo succeeded (${startMark.elapsedNow()})" }
                val openRegistration = result.data.registrationPolicy != RegistrationPolicy.CLOSED
                secureStorage.save(KEY_OPEN_REGISTRATION, openRegistration.toString())
                secureStorage.save(KEY_SETUP_REQUIRED, result.data.setupRequired.toString())

                val newState =
                    if (result.data.setupRequired) {
                        DomainAuthState.NeedsSetup
                    } else {
                        DomainAuthState.NeedsLogin(openRegistration = openRegistration)
                    }
                authState.value = newState
                newState
            }

            is AppResult.Failure -> {
                logger.info { "checkServerStatus: getServerInfo failed (${startMark.elapsedNow()}): ${result.message}" }
                val cachedOpenRegistration = getCachedOpenRegistration()
                authState.value = DomainAuthState.NeedsLogin(openRegistration = cachedOpenRegistration)
                DomainAuthState.NeedsLogin(openRegistration = cachedOpenRegistration)
            }
        }
    }

    private suspend fun getCachedOpenRegistration(): Boolean =
        secureStorage.read(KEY_OPEN_REGISTRATION)?.toBooleanStrictOrNull() ?: false

    private suspend fun getCachedSetupRequired(): Boolean =
        secureStorage.read(KEY_SETUP_REQUIRED)?.toBooleanStrictOrNull() ?: false

    override suspend fun refreshOpenRegistration() {
        val currentState = authState.value
        if (currentState !is DomainAuthState.NeedsLogin) return

        when (val result = instanceRepository.getServerInfo(forceRefresh = true)) {
            is AppResult.Success -> {
                val openRegistration = result.data.registrationPolicy != RegistrationPolicy.CLOSED
                secureStorage.save(KEY_OPEN_REGISTRATION, openRegistration.toString())
                if (authState.value is DomainAuthState.NeedsLogin) {
                    authState.value = DomainAuthState.NeedsLogin(openRegistration = openRegistration)
                }
            }

            is AppResult.Failure -> {
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

        authState.value =
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
        // Leaving the pending-approval state must drive navigation onward — back to login — so the
        // user is never stranded on the pending screen (e.g. tapping Cancel). Navigation is
        // AuthState-driven, so flip the state here rather than relying on a screen-level callback.
        // Callers that delete the server URL too (disconnect) re-derive state immediately after.
        authState.value = DomainAuthState.NeedsLogin(openRegistration = getCachedOpenRegistration())
    }

    private companion object {
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_SESSION_ID = "session_id"
        const val KEY_USER_ID = "user_id"
        const val KEY_OPEN_REGISTRATION = "open_registration"
        const val KEY_SETUP_REQUIRED = "setup_required"
        const val KEY_PENDING_USER_ID = "pending_user_id"
        const val KEY_PENDING_EMAIL = "pending_email"

        const val POLICY_STREAM_RETRY_MILLIS = 5_000L
    }
}
