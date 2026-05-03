package com.calypsan.listenup.client.data.repository

import io.github.oshai.kotlinlogging.KotlinLogging
import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.client.core.SecureStorage
import com.calypsan.listenup.client.core.ServerUrl
import com.calypsan.listenup.client.domain.model.ThemeMode
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import com.calypsan.listenup.client.domain.repository.PlaybackPreferences as DomainPlaybackPreferences
import kotlin.time.TimeSource
import com.calypsan.listenup.client.domain.repository.AuthState as DomainAuthState
import com.calypsan.listenup.client.domain.repository.PreferenceChangeEvent as DomainPreferenceChangeEvent
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.client.core.Failure

private val logger = KotlinLogging.logger {}

/**
 * Repository for managing application settings and authentication state.
 *
 * This is the single source of truth for:
 * - Server URL configuration
 * - Authentication tokens (access + refresh)
 * - Session information (user ID, session ID)
 * - Reactive authentication state via StateFlow
 *
 * All sensitive data is stored via SecureStorage (encrypted at rest).
 */
class SettingsRepositoryImpl(
    private val secureStorage: SecureStorage,
    private val instanceRepository: InstanceRepository,
) : com.calypsan.listenup.client.domain.repository.AuthSession,
    com.calypsan.listenup.client.domain.repository.ServerConfig,
    com.calypsan.listenup.client.domain.repository.LibrarySync,
    com.calypsan.listenup.client.domain.repository.LibraryPreferences,
    com.calypsan.listenup.client.domain.repository.PlaybackPreferences,
    com.calypsan.listenup.client.domain.repository.LocalPreferences {
    // Override properties can't use explicit backing fields - must use traditional pattern
    private val _authState = MutableStateFlow<DomainAuthState>(DomainAuthState.Initializing)
    override val authState: StateFlow<DomainAuthState> = _authState.asStateFlow()

    // Buffer of 1 ensures emit() doesn't suspend when no collectors are active.
    // This is appropriate for preference sync since we don't want settings changes
    // to block waiting for the sync layer.
    private val _preferenceChanges = MutableSharedFlow<DomainPreferenceChangeEvent>(extraBufferCapacity = 1)
    override val preferenceChanges: SharedFlow<DomainPreferenceChangeEvent> = _preferenceChanges.asSharedFlow()

    // Local preferences StateFlows (device-specific, NOT synced)
    private val _themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    override val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _dynamicColorsEnabled = MutableStateFlow(true)
    override val dynamicColorsEnabled: StateFlow<Boolean> = _dynamicColorsEnabled.asStateFlow()

    private val _autoRewindEnabled = MutableStateFlow(true)
    override val autoRewindEnabled: StateFlow<Boolean> = _autoRewindEnabled.asStateFlow()

    private val _wifiOnlyDownloads = MutableStateFlow(true)
    override val wifiOnlyDownloads: StateFlow<Boolean> = _wifiOnlyDownloads.asStateFlow()

    private val _autoRemoveFinished = MutableStateFlow(false)
    override val autoRemoveFinished: StateFlow<Boolean> = _autoRemoveFinished.asStateFlow()

    private val _hapticFeedbackEnabled = MutableStateFlow(true)
    override val hapticFeedbackEnabled: StateFlow<Boolean> = _hapticFeedbackEnabled.asStateFlow()

    companion object {
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_REMOTE_URL = "server_remote_url"
        private const val KEY_ACTIVE_URL = "active_url"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_OPEN_REGISTRATION = "open_registration"

        // Library identity (for detecting server reinstalls/resets)
        private const val KEY_CONNECTED_LIBRARY_ID = "connected_library_id"

        // Pending registration (waiting for admin approval)
        private const val KEY_PENDING_USER_ID = "pending_user_id"
        private const val KEY_PENDING_EMAIL = "pending_email"
        private const val KEY_PENDING_PASSWORD = "pending_password" // Encrypted

        // Library sort preferences (per-tab)
        private const val KEY_SORT_BOOKS = "sort_books"
        private const val KEY_SORT_SERIES = "sort_series"
        private const val KEY_SORT_AUTHORS = "sort_authors"
        private const val KEY_SORT_NARRATORS = "sort_narrators"

        // Title sort article handling
        private const val KEY_IGNORE_TITLE_ARTICLES = "ignore_title_articles"

        // Series display preferences
        private const val KEY_HIDE_SINGLE_BOOK_SERIES = "hide_single_book_series"

        // Playback preferences (synced)
        private const val KEY_SPATIAL_PLAYBACK = "spatial_playback"
        private const val KEY_DEFAULT_PLAYBACK_SPEED = "default_playback_speed"

        // Local preferences (device-specific, NOT synced)
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_DYNAMIC_COLORS = "dynamic_colors"
        private const val KEY_AUTO_REWIND = "auto_rewind"
        private const val KEY_WIFI_ONLY_DOWNLOADS = "wifi_only_downloads"
        private const val KEY_AUTO_REMOVE_FINISHED = "auto_remove_finished"
        private const val KEY_HAPTIC_FEEDBACK = "haptic_feedback"

        // Default values
        const val DEFAULT_PLAYBACK_SPEED = 1.0f
    }

    // Server configuration

    /**
     * Set the server URL for API requests.
     * This is persisted across app restarts and checks server status to determine
     * if setup (root user creation) is needed.
     */
    override suspend fun setServerUrl(url: ServerUrl) {
        logger.info { "setServerUrl: Saving URL ${url.value}" }
        val startMark = TimeSource.Monotonic.markNow()
        secureStorage.save(KEY_SERVER_URL, url.value)
        logger.info { "setServerUrl: URL saved (${startMark.elapsedNow()})" }

        // Check if we already have tokens (returning user)
        val hasToken = getAccessToken() != null
        logger.info { "setServerUrl: hasToken=$hasToken (${startMark.elapsedNow()})" }

        if (hasToken) {
            // Trust local tokens - derive state from stored data
            _authState.value = deriveAuthState()
            logger.info { "setServerUrl: Derived auth state (${startMark.elapsedNow()})" }
        } else {
            // No tokens - need to check server to determine if setup is required
            // This is the first time connecting, so check if server has users
            logger.info { "setServerUrl: Calling checkServerStatus (${startMark.elapsedNow()})" }
            checkServerStatus()
            logger.info { "setServerUrl: checkServerStatus completed (${startMark.elapsedNow()})" }
        }
    }

    /**
     * Get the configured server URL (local URL).
     * @return ServerUrl if configured, null otherwise
     */
    override suspend fun getServerUrl(): ServerUrl? = secureStorage.read(KEY_SERVER_URL)?.let { ServerUrl(it) }

    override suspend fun setRemoteUrl(url: String?) {
        if (url != null) {
            secureStorage.save(KEY_REMOTE_URL, url)
        } else {
            secureStorage.delete(KEY_REMOTE_URL)
        }
    }

    override suspend fun getRemoteUrl(): ServerUrl? = secureStorage.read(KEY_REMOTE_URL)?.let { ServerUrl(it) }

    override suspend fun getActiveUrl(): ServerUrl? {
        val activeUrl = secureStorage.read(KEY_ACTIVE_URL)
        if (activeUrl != null) return ServerUrl(activeUrl)
        // Default to local URL
        return getServerUrl() ?: getRemoteUrl()
    }

    override suspend fun switchToFallbackUrl(): ServerUrl? {
        val currentActive = secureStorage.read(KEY_ACTIVE_URL) ?: secureStorage.read(KEY_SERVER_URL)
        val localUrl = secureStorage.read(KEY_SERVER_URL)
        val remoteUrl = secureStorage.read(KEY_REMOTE_URL)

        // Switch to the other URL
        val fallback =
            when (currentActive) {
                localUrl -> remoteUrl
                remoteUrl -> localUrl
                else -> remoteUrl ?: localUrl
            } ?: return null

        secureStorage.save(KEY_ACTIVE_URL, fallback)
        logger.info { "Switched active URL to: $fallback" }
        return ServerUrl(fallback)
    }

    override suspend fun preferLocalUrl() {
        val localUrl = secureStorage.read(KEY_SERVER_URL)
        if (localUrl != null) {
            secureStorage.save(KEY_ACTIVE_URL, localUrl)
        }
    }

    // Authentication state management

    /**
     * Save authentication tokens after successful login or token refresh.
     * This updates the auth state to Authenticated.
     *
     * @param access PASETO access token (15min lifetime)
     * @param refresh Opaque refresh token (30d lifetime)
     * @param sessionId Server session ID
     * @param userId Authenticated user's ID
     */
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

        _authState.value = DomainAuthState.Authenticated(userId, sessionId)
    }

    /**
     * Get the current access token.
     * @return AccessToken if authenticated, null otherwise
     */
    override suspend fun getAccessToken(): AccessToken? = secureStorage.read(KEY_ACCESS_TOKEN)?.let { AccessToken(it) }

    /**
     * Get the current refresh token.
     * @return RefreshToken if authenticated, null otherwise
     */
    override suspend fun getRefreshToken(): RefreshToken? =
        secureStorage.read(KEY_REFRESH_TOKEN)?.let { RefreshToken(it) }

    /**
     * Get the current session ID.
     * @return Session ID if authenticated, null otherwise
     */
    override suspend fun getSessionId(): String? = secureStorage.read(KEY_SESSION_ID)

    /**
     * Get the current user ID.
     * @return User ID if authenticated, null otherwise
     */
    override suspend fun getUserId(): String? = secureStorage.read(KEY_USER_ID)

    /**
     * Update only the access token (used during automatic token refresh).
     * Does not change auth state - assumes user is still authenticated.
     */
    override suspend fun updateAccessToken(token: AccessToken) {
        secureStorage.save(KEY_ACCESS_TOKEN, token.value)
    }

    // Session management

    /**
     * Clear authentication tokens (soft logout).
     * Keeps server URL and cached data intact.
     * Sets state directly to NeedsLogin without making network calls.
     *
     * This is called when:
     * - User explicitly logs out
     * - Token refresh fails (401 from server)
     *
     * We go directly to NeedsLogin (not CheckingServer) because:
     * 1. We know the server was reachable (we just got a 401)
     * 2. Making another HTTP call during auth failure can cause issues
     * 3. Server setup status rarely changes during a session
     *
     * Playback resilience handled in LogoutUseCase (clears playback before auth tokens).
     * If playing, show banner instead of redirecting to login.
     */
    override suspend fun clearAuthTokens() {
        secureStorage.delete(KEY_ACCESS_TOKEN)
        secureStorage.delete(KEY_REFRESH_TOKEN)
        secureStorage.delete(KEY_SESSION_ID)
        secureStorage.delete(KEY_USER_ID)

        // Go directly to NeedsLogin - don't make HTTP calls during auth failure
        // The server was reachable (we got a 401), so setup isn't required
        // Use cached open registration value if available
        val cachedOpenRegistration = getCachedOpenRegistration()
        _authState.value = DomainAuthState.NeedsLogin(openRegistration = cachedOpenRegistration)
    }

    /**
     * Clear all settings including server URL (complete reset).
     * Updates auth state to NeedsServerUrl.
     */
    override suspend fun clearAll() {
        secureStorage.clear()
        _authState.value = DomainAuthState.NeedsServerUrl
    }

    // State queries

    /**
     * Check if user has stored authentication tokens.
     * Note: Tokens may be expired - this only checks for presence.
     */
    override suspend fun isAuthenticated(): Boolean = getAccessToken() != null

    /**
     * Check if server URL has been configured.
     */
    override suspend fun hasServerConfigured(): Boolean = getServerUrl() != null

    /**
     * Initialize authentication state on app startup.
     * Call this once during app initialization to derive auth state from stored data.
     *
     * State derivation logic (offline-first):
     * ```
     * No URL               → NeedsServerUrl
     * URL + Token          → Authenticated (trust local tokens)
     * URL + No Token       → NeedsLogin (don't check network on startup)
     * ```
     *
     * We deliberately skip network checks on startup. If tokens are invalid,
     * they'll fail when used and trigger re-auth via 401 handling.
     * This ensures the app works offline with cached data.
     */
    override suspend fun initializeAuthState() {
        _authState.value = deriveAuthState()
    }

    /**
     * Derives the current authentication state based on stored data.
     *
     * Offline-first: We never make network calls during state derivation.
     * If tokens exist, we trust them. If they're invalid, API calls will
     * fail with 401 and trigger re-auth at that point.
     */
    private suspend fun deriveAuthState(): DomainAuthState {
        val serverUrl = getServerUrl()

        // No URL configured → need server setup
        if (serverUrl == null) {
            return DomainAuthState.NeedsServerUrl
        }

        // URL + Token = Authenticated (trust local state)
        val hasToken = getAccessToken() != null
        val userId = getUserId()
        val sessionId = getSessionId()

        if (hasToken && userId != null && sessionId != null) {
            return DomainAuthState.Authenticated(userId, sessionId)
        }

        // URL + Token but missing userId/sessionId → inconsistent state
        // This can happen if app crashed during token save or storage was corrupted.
        // Clear the invalid tokens and require fresh login - never use placeholders
        // as they cause "Unknown User" to appear in the UI.
        if (hasToken) {
            clearAuthTokens()
            return DomainAuthState.NeedsLogin(openRegistration = getCachedOpenRegistration())
        }

        // Check for pending registration (user registered but waiting for approval)
        val pendingRegistration = getPendingRegistration()
        if (pendingRegistration != null) {
            val (pendingUserId, pendingEmail, pendingPassword) = pendingRegistration
            return DomainAuthState.PendingApproval(
                userId = pendingUserId,
                email = pendingEmail,
                encryptedPassword = pendingPassword,
            )
        }

        // URL but no token → user needs to log in
        // Don't check server status - that's a network call
        // Use cached open registration value if available
        val cachedOpenRegistration = getCachedOpenRegistration()
        return DomainAuthState.NeedsLogin(openRegistration = cachedOpenRegistration)
    }

    /**
     * Checks the server's instance status to determine if setup is required.
     * Returns NeedsSetup if server has no root user, NeedsLogin otherwise.
     *
     * Note: This is only called explicitly (e.g., from login screen), never on startup.
     * On network failure, we stay in NeedsLogin - the server URL is never cleared
     * automatically. Users must explicitly change servers via settings.
     */
    override suspend fun checkServerStatus(): DomainAuthState {
        logger.info { "checkServerStatus: Starting" }
        val startMark = TimeSource.Monotonic.markNow()
        _authState.value = DomainAuthState.CheckingServer

        logger.info { "checkServerStatus: Calling getInstance" }
        return when (val result = instanceRepository.getInstance(forceRefresh = true)) {
            is Success -> {
                logger.info { "checkServerStatus: getInstance succeeded (${startMark.elapsedNow()})" }
                // Cache open registration status for use when deriving auth state
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
                // Server unreachable - stay in NeedsLogin with cached open registration value
                // User can retry or check their connection
                val cachedOpenRegistration = getCachedOpenRegistration()
                _authState.value = DomainAuthState.NeedsLogin(openRegistration = cachedOpenRegistration)
                DomainAuthState.NeedsLogin(openRegistration = cachedOpenRegistration)
            }
        }
    }

    /**
     * Get the cached open registration value from storage.
     * Returns false if not cached.
     */
    private suspend fun getCachedOpenRegistration(): Boolean =
        secureStorage.read(KEY_OPEN_REGISTRATION)?.toBooleanStrictOrNull() ?: false

    /**
     * Refresh open registration status from server without changing auth state to CheckingServer.
     * Updates the NeedsLogin state directly if we're currently in that state.
     */
    override suspend fun refreshOpenRegistration() {
        // Only refresh if we're in NeedsLogin state
        val currentState = _authState.value
        if (currentState !is DomainAuthState.NeedsLogin) return

        when (val result = instanceRepository.getInstance(forceRefresh = true)) {
            is Success -> {
                // Cache the value
                secureStorage.save(KEY_OPEN_REGISTRATION, result.data.openRegistration.toString())
                // Update state with new value (only if still in NeedsLogin)
                if (_authState.value is DomainAuthState.NeedsLogin) {
                    _authState.value = DomainAuthState.NeedsLogin(openRegistration = result.data.openRegistration)
                }
            }

            is Failure -> {
                // Silently fail - keep existing cached value
            }
        }
    }

    /**
     * Explicitly disconnect from the current server.
     *
     * This clears the server URL and all auth data, returning to the
     * initial server setup screen. Only call this when the user
     * explicitly wants to change servers (e.g., from a "Change Server" button).
     *
     * For network errors, do NOT call this - let the user retry or work offline.
     */
    override suspend fun disconnectFromServer() {
        secureStorage.delete(KEY_SERVER_URL)
        secureStorage.delete(KEY_ACCESS_TOKEN)
        secureStorage.delete(KEY_REFRESH_TOKEN)
        secureStorage.delete(KEY_SESSION_ID)
        secureStorage.delete(KEY_USER_ID)
        secureStorage.delete(KEY_OPEN_REGISTRATION)
        secureStorage.delete(KEY_CONNECTED_LIBRARY_ID)
        secureStorage.delete(KEY_REMOTE_URL)
        secureStorage.delete(KEY_ACTIVE_URL)
        _authState.value = DomainAuthState.NeedsServerUrl
    }

    // Library sync identity

    /**
     * Get the library ID this client is synced with.
     * Returns null on first connection to a server.
     */
    override suspend fun getConnectedLibraryId(): String? = secureStorage.read(KEY_CONNECTED_LIBRARY_ID)

    /**
     * Store the library ID after successful sync verification.
     * This ID is used to detect when the server's library has changed.
     */
    override suspend fun setConnectedLibraryId(libraryId: String) {
        secureStorage.save(KEY_CONNECTED_LIBRARY_ID, libraryId)
    }

    /**
     * Clear the connected library ID.
     * Called when resetting local data after a library mismatch.
     */
    override suspend fun clearConnectedLibraryId() {
        secureStorage.delete(KEY_CONNECTED_LIBRARY_ID)
    }

    // Library sort preferences
    // Stored as "category:direction" (e.g., "title:ascending")

    /**
     * Get the sort state for the Books tab.
     * @return Persistence key (category:direction), or null if not set
     */
    override suspend fun getBooksSortState(): String? = secureStorage.read(KEY_SORT_BOOKS)

    /**
     * Set the sort state for the Books tab.
     */
    override suspend fun setBooksSortState(persistenceKey: String) {
        secureStorage.save(KEY_SORT_BOOKS, persistenceKey)
    }

    /**
     * Get the sort state for the Series tab.
     * @return Persistence key (category:direction), or null if not set
     */
    override suspend fun getSeriesSortState(): String? = secureStorage.read(KEY_SORT_SERIES)

    /**
     * Set the sort state for the Series tab.
     */
    override suspend fun setSeriesSortState(persistenceKey: String) {
        secureStorage.save(KEY_SORT_SERIES, persistenceKey)
    }

    /**
     * Get the sort state for the Authors tab.
     * @return Persistence key (category:direction), or null if not set
     */
    override suspend fun getAuthorsSortState(): String? = secureStorage.read(KEY_SORT_AUTHORS)

    /**
     * Set the sort state for the Authors tab.
     */
    override suspend fun setAuthorsSortState(persistenceKey: String) {
        secureStorage.save(KEY_SORT_AUTHORS, persistenceKey)
    }

    /**
     * Get the sort state for the Narrators tab.
     * @return Persistence key (category:direction), or null if not set
     */
    override suspend fun getNarratorsSortState(): String? = secureStorage.read(KEY_SORT_NARRATORS)

    /**
     * Set the sort state for the Narrators tab.
     */
    override suspend fun setNarratorsSortState(persistenceKey: String) {
        secureStorage.save(KEY_SORT_NARRATORS, persistenceKey)
    }

    // Title sort article handling

    /**
     * Get whether to ignore leading articles (A, An, The) when sorting by title.
     * @return true to ignore articles (default), false for literal sort
     */
    override suspend fun getIgnoreTitleArticles(): Boolean =
        secureStorage.read(KEY_IGNORE_TITLE_ARTICLES)?.toBooleanStrictOrNull() ?: true

    /**
     * Set whether to ignore leading articles when sorting by title.
     */
    override suspend fun setIgnoreTitleArticles(ignore: Boolean) {
        secureStorage.save(KEY_IGNORE_TITLE_ARTICLES, ignore.toString())
    }

    // Series display preferences

    /**
     * Get whether to hide series with only one book.
     * @return true to hide single-book series (default), false to show all
     */
    override suspend fun getHideSingleBookSeries(): Boolean =
        secureStorage.read(KEY_HIDE_SINGLE_BOOK_SERIES)?.toBooleanStrictOrNull() ?: true

    /**
     * Set whether to hide series with only one book.
     */
    override suspend fun setHideSingleBookSeries(hide: Boolean) {
        secureStorage.save(KEY_HIDE_SINGLE_BOOK_SERIES, hide.toString())
    }

    // Playback preferences

    /**
     * Get whether spatial (5.1 surround) audio is preferred.
     * When enabled, transcoded audio uses 5.1 surround for spatial audio support.
     * When disabled, uses stereo for faster transcoding and universal compatibility.
     * @return true for 5.1 spatial (default), false for stereo
     */
    override suspend fun getSpatialPlayback(): Boolean =
        secureStorage.read(KEY_SPATIAL_PLAYBACK)?.toBooleanStrictOrNull() ?: true

    /**
     * Set spatial audio preference.
     * This is a per-device setting - different devices can have different preferences.
     */
    override suspend fun setSpatialPlayback(enabled: Boolean) {
        secureStorage.save(KEY_SPATIAL_PLAYBACK, enabled.toString())
    }

    // Universal playback speed (synced across devices)

    override fun observeDefaultPlaybackSpeed(): Flow<Float> =
        flow {
            // Initial emit — best-effort read; fall back to the constant on non-cancellation error.
            val initial =
                try {
                    getDefaultPlaybackSpeed()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn(e) { "Initial getDefaultPlaybackSpeed failed; falling back to default." }
                    DomainPlaybackPreferences.DEFAULT_PLAYBACK_SPEED
                }
            emit(initial)

            // Re-emits driven by setDefaultPlaybackSpeed → preferenceChanges.PlaybackSpeedChanged.
            preferenceChanges
                .filterIsInstance<DomainPreferenceChangeEvent.PlaybackSpeedChanged>()
                .map { it.speed }
                .collect { emit(it) }
        }

    /**
     * Get the default playback speed for new books.
     * This is a synced setting - the value may be updated when preferences sync.
     * @return Playback speed multiplier (e.g., 1.0, 1.25, 1.5). Default is 1.0.
     */
    override suspend fun getDefaultPlaybackSpeed(): Float =
        secureStorage.read(KEY_DEFAULT_PLAYBACK_SPEED)?.toFloatOrNull() ?: DEFAULT_PLAYBACK_SPEED

    /**
     * Set the default playback speed for new books.
     * This is a synced setting - emits an event for the sync layer to queue.
     * @param speed Playback speed multiplier (e.g., 1.0, 1.25, 1.5)
     */
    override suspend fun setDefaultPlaybackSpeed(speed: Float) {
        // Save locally
        secureStorage.save(KEY_DEFAULT_PLAYBACK_SPEED, speed.toString())

        // Emit event for sync layer to observe and queue
        _preferenceChanges.emit(DomainPreferenceChangeEvent.PlaybackSpeedChanged(speed))
    }

    // Local preferences (device-specific, NOT synced)

    /**
     * Initialize local preferences from storage.
     * Call this during app startup to hydrate StateFlows from persisted values.
     */
    override suspend fun initializeLocalPreferences() {
        _themeMode.value = ThemeMode.fromString(secureStorage.read(KEY_THEME_MODE))
        _dynamicColorsEnabled.value =
            secureStorage.read(KEY_DYNAMIC_COLORS)?.toBooleanStrictOrNull() ?: true
        _autoRewindEnabled.value =
            secureStorage.read(KEY_AUTO_REWIND)?.toBooleanStrictOrNull() ?: true
        _wifiOnlyDownloads.value =
            secureStorage.read(KEY_WIFI_ONLY_DOWNLOADS)?.toBooleanStrictOrNull() ?: true
        _autoRemoveFinished.value =
            secureStorage.read(KEY_AUTO_REMOVE_FINISHED)?.toBooleanStrictOrNull() ?: false
        _hapticFeedbackEnabled.value =
            secureStorage.read(KEY_HAPTIC_FEEDBACK)?.toBooleanStrictOrNull() ?: true
    }

    /**
     * Set the theme mode (system/light/dark).
     * This is a device-local setting - does NOT sync to server.
     */
    override suspend fun setThemeMode(mode: ThemeMode) {
        secureStorage.save(KEY_THEME_MODE, mode.toStorageString())
        _themeMode.value = mode
    }

    /**
     * Set whether to use dynamic (wallpaper-based) colors.
     * Only affects Android 12+. Falls back to static colors on older versions.
     * This is a device-local setting - does NOT sync to server.
     */
    override suspend fun setDynamicColorsEnabled(enabled: Boolean) {
        secureStorage.save(KEY_DYNAMIC_COLORS, enabled.toString())
        _dynamicColorsEnabled.value = enabled
    }

    /**
     * Set whether to auto-rewind when resuming playback.
     * When enabled, playback rewinds a few seconds on resume to help recall context.
     * This is a device-local setting - does NOT sync to server.
     */
    override suspend fun setAutoRewindEnabled(enabled: Boolean) {
        secureStorage.save(KEY_AUTO_REWIND, enabled.toString())
        _autoRewindEnabled.value = enabled
    }

    /**
     * Set whether to only download on WiFi.
     * When enabled, downloads pause on cellular and resume on WiFi.
     * This is a device-local setting - does NOT sync to server.
     */
    override suspend fun setWifiOnlyDownloads(enabled: Boolean) {
        secureStorage.save(KEY_WIFI_ONLY_DOWNLOADS, enabled.toString())
        _wifiOnlyDownloads.value = enabled
    }

    /**
     * Set whether to auto-remove downloads after finishing a book.
     * When enabled, completed books are removed to save storage space.
     * This is a device-local setting - does NOT sync to server.
     */
    override suspend fun setAutoRemoveFinished(enabled: Boolean) {
        secureStorage.save(KEY_AUTO_REMOVE_FINISHED, enabled.toString())
        _autoRemoveFinished.value = enabled
    }

    /**
     * Set whether to enable haptic feedback on controls.
     * When enabled, tapping player controls provides tactile feedback.
     * This is a device-local setting - does NOT sync to server.
     */
    override suspend fun setHapticFeedbackEnabled(enabled: Boolean) {
        secureStorage.save(KEY_HAPTIC_FEEDBACK, enabled.toString())
        _hapticFeedbackEnabled.value = enabled
    }

    // Pending registration methods

    /**
     * Save pending registration state after submitting registration.
     * Stores credentials securely for auto-login after admin approval.
     */
    override suspend fun savePendingRegistration(
        userId: String,
        email: String,
        password: String,
    ) {
        secureStorage.save(KEY_PENDING_USER_ID, userId)
        secureStorage.save(KEY_PENDING_EMAIL, email)
        secureStorage.save(KEY_PENDING_PASSWORD, password)

        // Update auth state to PendingApproval
        _authState.value =
            DomainAuthState.PendingApproval(
                userId = userId,
                email = email,
                encryptedPassword = password,
            )
    }

    /**
     * Get pending registration credentials for auto-login.
     * @return Triple of (userId, email, password) if pending, null otherwise
     */
    override suspend fun getPendingRegistration(): Triple<String, String, String>? {
        val userId = secureStorage.read(KEY_PENDING_USER_ID) ?: return null
        val email = secureStorage.read(KEY_PENDING_EMAIL) ?: return null
        val password = secureStorage.read(KEY_PENDING_PASSWORD) ?: return null
        return Triple(userId, email, password)
    }

    /**
     * Clear pending registration state.
     * Called after successful login or when registration is denied.
     */
    override suspend fun clearPendingRegistration() {
        secureStorage.delete(KEY_PENDING_USER_ID)
        secureStorage.delete(KEY_PENDING_EMAIL)
        secureStorage.delete(KEY_PENDING_PASSWORD)
    }
}
