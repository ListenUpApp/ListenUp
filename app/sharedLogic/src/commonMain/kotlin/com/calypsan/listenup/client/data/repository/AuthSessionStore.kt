package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.core.SecureStorage
import com.calypsan.listenup.core.SecureStorageUnavailableException
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import com.calypsan.listenup.client.domain.repository.PendingRegistration
import com.calypsan.listenup.client.domain.repository.RegistrationPolicyStream
import com.calypsan.listenup.client.domain.repository.ServerConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
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

    /**
     * Serializes the auth-epoch check/bump with the credential writes it guards, so the epoch a
     * refresh captured can't change between [saveAuthTokens]'s guard read and its writes (C8/C9).
     */
    private val credentialMutex = Mutex()
    private var authEpoch: Long = 0

    override suspend fun currentAuthEpoch(): Long = credentialMutex.withLock { authEpoch }

    init {
        observeRegistrationPolicy()
    }

    /**
     * Keeps `openRegistration` live while the login screen is showing: subscribes to the server's
     * registration-policy RPC watch only in [DomainAuthState.NeedsLogin] and flips the Sign Up
     * affordance the instant an admin closes (or reopens) registration — no relaunch, no
     * pull-to-refresh.
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
                    // streamPolicy() owns reconnect (resubscribe with capped exponential backoff),
                    // so a dropped watch self-heals — no bespoke retryWhen here.
                    if (onLoginScreen) policyStream.streamPolicy() else emptyFlow()
                }.collect { policy ->
                    applyOpenRegistration(policy != RegistrationPolicy.CLOSED)
                }
        }
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
        ifEpoch: Long?,
    ) {
        credentialMutex.withLock {
            // Epoch guard (C8): a refresh that captured an epoch which a logout has since bumped must
            // NOT resurrect the signed-out session. Login/register/setup pass null → unconditional.
            if (ifEpoch != null && ifEpoch != authEpoch) {
                logger.info {
                    "saveAuthTokens skipped: auth epoch advanced ($ifEpoch → $authEpoch) — session ended mid-refresh"
                }
                return
            }

            // Write order (C9): refresh → session → user → access. The access token is the readiness
            // signal a concurrent reader keys on, so it lands LAST — never a new access token paired
            // with a stale refresh token.
            //
            // The server's lost-reply rule makes this order load-bearing for the session itself: a
            // process death after writing the new access token but before the new refresh token would
            // leave the OLD refresh token beside an access token that confirms the new rotation, and
            // the next refresh would then revoke the whole family. Refresh first (each save durable)
            // rules that out; a torn write lands the recoverable way round — new refresh, old access.
            // Writing both atomically would remove even that; don't reorder these lines.
            secureStorage.save(KEY_REFRESH_TOKEN, refresh.value)
            secureStorage.save(KEY_SESSION_ID, sessionId)
            secureStorage.save(KEY_USER_ID, userId)
            secureStorage.save(KEY_ACCESS_TOKEN, access.value)

            authState.value = DomainAuthState.Authenticated(UserId(userId), SessionId(sessionId))
        }
    }

    override suspend fun getAccessToken(): AccessToken? = secureStorage.read(KEY_ACCESS_TOKEN)?.let { AccessToken(it) }

    /**
     * Null only when no refresh token is stored. One that is stored but unreadable right now throws
     * [com.calypsan.listenup.core.SecureStorageUnavailableException]: read as absent, it ended the
     * session over a Keystore blip.
     */
    override suspend fun getRefreshToken(): RefreshToken? =
        secureStorage.readCredential(KEY_REFRESH_TOKEN)?.let { RefreshToken(it) }

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
        credentialMutex.withLock { clearAuthTokensLocked() }
    }

    /** Full credential wipe under the [credentialMutex]. Bumps the auth epoch so an in-flight refresh can't resurrect. */
    private suspend fun clearAuthTokensLocked() {
        authEpoch++
        secureStorage.delete(KEY_ACCESS_TOKEN)
        secureStorage.delete(KEY_REFRESH_TOKEN)
        secureStorage.delete(KEY_SESSION_ID)
        secureStorage.delete(KEY_USER_ID)

        authState.value = DomainAuthState.NeedsLogin(openRegistration = getCachedOpenRegistration())
    }

    override suspend fun clearSessionCredentials() {
        credentialMutex.withLock {
            // Bump the epoch first: a concurrent late refresh that already captured the old epoch must
            // not re-persist over this lapse (C8). [clearAuthTokensLocked] bumps it too; guard against
            // double-invalidation by reading userId under the same lock.
            val userId = getUserId()
            if (userId == null) {
                // No persisted identity to lapse into (fresh install / already signed out) —
                // fall back to the full clear rather than invent a SessionLapsed without a user.
                clearAuthTokensLocked()
                return
            }
            authEpoch++
            secureStorage.delete(KEY_ACCESS_TOKEN)
            secureStorage.delete(KEY_REFRESH_TOKEN)
            secureStorage.delete(KEY_SESSION_ID)

            authState.value = DomainAuthState.SessionLapsed(UserId(userId))
        }
    }

    override suspend fun isAuthenticated(): Boolean = getAccessToken() != null

    /**
     * Recompute auth state on boot.
     *
     * Offline for every state a signed-in device can be in — invalid tokens surface later as 401s
     * and trigger re-auth, rather than being probed for here. The one branch that reaches the
     * network is the terminal one, where no session exists at all and only the server can say
     * whether the reader should be setting the instance up or signing in to it. See
     * [deriveAuthState].
     */
    override suspend fun initializeAuthState() {
        authState.value =
            try {
                deriveAuthState()
            } catch (e: SecureStorageUnavailableException) {
                // A storage that throws on a plain read (Apple's Keychain before first unlock) must
                // not take cold start down with it, nor decide anything destructive: sign-in for this
                // launch, every credential left where it is.
                logger.warn {
                    "Cold start couldn't read '${e.key}'; showing sign-in without touching stored credentials"
                }
                DomainAuthState.NeedsLogin(openRegistration = false)
            }
    }

    private suspend fun deriveAuthState(): DomainAuthState {
        val serverUrl = serverConfig.getServerUrl()
        if (serverUrl == null) {
            return DomainAuthState.NeedsServerUrl
        }

        val credentials = readSessionCredentials()
        if (credentials == null) {
            // Still unreadable after every retry. Nothing here is corrupt — the Keystore is out —
            // so nothing is deleted: this launch signs in, and the next launch finds it all intact.
            logger.warn {
                "Session credentials still unreadable after every retry; sign-in for this launch, nothing deleted"
            }
            return DomainAuthState.NeedsLogin(openRegistration = getCachedOpenRegistration())
        }
        val (hasToken, userId, sessionId) = credentials

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

        // Nobody is signed in on this device, so "set this server up, or sign in to it?" is a
        // question only the server can answer — and it is the one boot where the reader's very next
        // action needs the network anyway. Every signed-in boot has already returned above, so this
        // costs nothing in the common case.
        //
        // This used to be answered from a cached flag, which stranded the reader in both
        // directions. A stale "true" — cached on a first boot against an empty server, outliving an
        // admin created anywhere else — offered to create an admin the server already had;
        // `SetupViewModel` has no init that re-checks, only a `handleFailure` branch for
        // `SetupAlreadyComplete`, so the reader discovered it by filling in the whole form and
        // being told it was pointless. A stale "false" hid setup on a server whose users had been
        // wiped, pinning them to a sign-in screen no account could satisfy. There is no cached
        // answer to go stale now.
        //
        // [checkServerStatus] falls back to sign-in when the server cannot be reached, which is the
        // safe direction: with no answer available a sign-in screen is merely useless, where a
        // setup form would be actively wrong.
        return checkServerStatus()
    }

    /** What cold start knows about the held session: whether an access token is stored, and whose. */
    private data class SessionCredentials(
        val hasAccessToken: Boolean,
        val userId: String?,
        val sessionId: String?,
    )

    /**
     * Reads the session's credentials for [deriveAuthState] without mistaking "unreadable right now"
     * for "absent" — the difference between a Keystore blip and a wipe. Through the folding `read()`,
     * a blip on `user_id` beside a readable access token looked like the corruption branch and
     * cleared every credential; a blip on the access token looked like a lapsed session.
     *
     * An unreadable credential is retried on [CREDENTIAL_READ_RETRY_DELAYS]. An access token that
     * stays unreadable is still a stored one: its bytes are on disk, so the session is held and the
     * next authenticated call decides. An identity that stays unreadable past every retry returns
     * null — never "absent": the corruption self-heal (`clearAuthTokens`) is only for data that is
     * genuinely missing, because wiping on an outage destroys a session whose bytes are all intact.
     */
    private suspend fun readSessionCredentials(): SessionCredentials? {
        for (wait in CREDENTIAL_READ_RETRY_DELAYS) {
            try {
                return SessionCredentials(
                    hasAccessToken = accessTokenIsStored(),
                    userId = secureStorage.readCredential(KEY_USER_ID),
                    sessionId = secureStorage.readCredential(KEY_SESSION_ID),
                )
            } catch (e: SecureStorageUnavailableException) {
                logger.warn { "Cold start couldn't read '${e.key}' yet; retrying in $wait" }
                delay(wait)
            }
        }
        return try {
            SessionCredentials(
                hasAccessToken = accessTokenIsStored(),
                userId = secureStorage.readCredential(KEY_USER_ID),
                sessionId = secureStorage.readCredential(KEY_SESSION_ID),
            )
        } catch (_: SecureStorageUnavailableException) {
            null
        }
    }

    private suspend fun accessTokenIsStored(): Boolean =
        try {
            secureStorage.readCredential(KEY_ACCESS_TOKEN) != null
        } catch (_: SecureStorageUnavailableException) {
            true
        }

    /**
     * Hit the server's instance endpoint to learn whether setup is required.
     *
     * `setupRequired` is used and discarded, never persisted: it is true only of the instant it was
     * read, and a remembered copy strands the reader the moment the server stops agreeing with it
     * (see [deriveAuthState]). `openRegistration` IS cached, because it only decides whether to
     * show a "Create account" link — being wrong about it costs a dead link, not a dead end.
     *
     * On network failure we stay in NeedsLogin — never blow away the URL automatically.
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
        try {
            secureStorage.read(KEY_OPEN_REGISTRATION)?.toBooleanStrictOrNull() == true
        } catch (_: SecureStorageUnavailableException) {
            false
        }

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
        /** Waits between cold-start credential reads while the Keystore is unavailable: ~5 s in all. */
        val CREDENTIAL_READ_RETRY_DELAYS = listOf(250.milliseconds, 1.seconds, 4.seconds)

        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_SESSION_ID = "session_id"
        const val KEY_USER_ID = "user_id"
        const val KEY_OPEN_REGISTRATION = "open_registration"
        const val KEY_PENDING_USER_ID = "pending_user_id"
        const val KEY_PENDING_EMAIL = "pending_email"
    }
}
