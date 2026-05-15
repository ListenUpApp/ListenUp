package com.calypsan.listenup.client.data.repository

import io.github.oshai.kotlinlogging.KotlinLogging
import com.calypsan.listenup.client.core.SecureStorage
import com.calypsan.listenup.client.core.ServerUrl
import com.calypsan.listenup.client.domain.model.ThemeMode
import com.calypsan.listenup.client.domain.repository.AuthSession
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
import com.calypsan.listenup.client.domain.repository.PreferenceChangeEvent as DomainPreferenceChangeEvent

private val logger = KotlinLogging.logger {}

/**
 * Single source of truth for everything *non-auth* in app configuration:
 * server URL plumbing (local/remote/active), library identity, library +
 * playback preferences, and device-local UI preferences. The authentication
 * slice (tokens, AuthState flow, pending-registration) lives in
 * `AuthSessionStore` — Settings calls into it for the cross-system seams
 * (set/clear server URL must update auth state).
 *
 * `authSession` is injected as `Lazy<AuthSession>` to break the
 * construction-time cycle: `SettingsRepositoryImpl` → `AuthSession` →
 * `AuthSessionStore(serverConfig)` → `ServerConfig` → `SettingsRepositoryImpl`.
 * `authSession` is only dereferenced inside suspend methods, never at
 * construction or in `init`, so the lazy wrapper is safe.
 *
 * All sensitive values are stored via `SecureStorage` (encrypted at rest).
 */
class SettingsRepositoryImpl(
    private val secureStorage: SecureStorage,
    authSession: Lazy<AuthSession>,
) : com.calypsan.listenup.client.domain.repository.ServerConfig,
    com.calypsan.listenup.client.domain.repository.LibrarySync,
    com.calypsan.listenup.client.domain.repository.LibraryPreferences,
    com.calypsan.listenup.client.domain.repository.PlaybackPreferences,
    com.calypsan.listenup.client.domain.repository.LocalPreferences {
    // Deferred to first suspend-method use; never read at construction time.
    private val authSession by authSession

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

        // Library identity (for detecting server reinstalls/resets)
        private const val KEY_CONNECTED_LIBRARY_ID = "connected_library_id"

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
     * Persist a new server URL and refresh the auth state to match. If the
     * device already has tokens for this URL we trust them (offline-first);
     * otherwise we go to the network to learn whether setup is required.
     */
    override suspend fun setServerUrl(url: ServerUrl) {
        logger.info { "setServerUrl: Saving URL ${url.value}" }
        val startMark = TimeSource.Monotonic.markNow()
        secureStorage.save(KEY_SERVER_URL, url.value)
        logger.info { "setServerUrl: URL saved (${startMark.elapsedNow()})" }

        if (authSession.isAuthenticated()) {
            authSession.initializeAuthState()
            logger.info { "setServerUrl: Derived auth state (${startMark.elapsedNow()})" }
        } else {
            logger.info { "setServerUrl: Calling checkServerStatus (${startMark.elapsedNow()})" }
            authSession.checkServerStatus()
            logger.info { "setServerUrl: checkServerStatus completed (${startMark.elapsedNow()})" }
        }
    }

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
        return getServerUrl() ?: getRemoteUrl()
    }

    override suspend fun switchToFallbackUrl(): ServerUrl? {
        val currentActive = secureStorage.read(KEY_ACTIVE_URL) ?: secureStorage.read(KEY_SERVER_URL)
        val localUrl = secureStorage.read(KEY_SERVER_URL)
        val remoteUrl = secureStorage.read(KEY_REMOTE_URL)

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

    override suspend fun hasServerConfigured(): Boolean = getServerUrl() != null

    /**
     * Wipe everything (server URL, library IDs, prefs, *and* auth) and reset
     * to the initial server-setup state. Used by destructive flows like
     * "switch server."
     */
    override suspend fun clearAll() {
        secureStorage.clear()
        authSession.initializeAuthState()
    }

    /**
     * Explicit user-driven server disconnect. Drops auth + URL plumbing +
     * library identity, leaves device-local preferences alone, and lands us
     * back on the server-selection screen. Network errors should *not* call
     * this — they let the user retry or work offline.
     */
    override suspend fun disconnectFromServer() {
        authSession.clearAuthTokens()
        authSession.clearPendingRegistration()
        secureStorage.delete(KEY_SERVER_URL)
        secureStorage.delete(KEY_REMOTE_URL)
        secureStorage.delete(KEY_ACTIVE_URL)
        secureStorage.delete(KEY_CONNECTED_LIBRARY_ID)
        authSession.initializeAuthState()
    }

    // Library sync identity

    override suspend fun getConnectedLibraryId(): String? = secureStorage.read(KEY_CONNECTED_LIBRARY_ID)

    override suspend fun setConnectedLibraryId(libraryId: String) {
        secureStorage.save(KEY_CONNECTED_LIBRARY_ID, libraryId)
    }

    override suspend fun clearConnectedLibraryId() {
        secureStorage.delete(KEY_CONNECTED_LIBRARY_ID)
    }

    // Library sort preferences (stored as "category:direction", e.g. "title:ascending")

    override suspend fun getBooksSortState(): String? = secureStorage.read(KEY_SORT_BOOKS)

    override suspend fun setBooksSortState(persistenceKey: String) {
        secureStorage.save(KEY_SORT_BOOKS, persistenceKey)
    }

    override suspend fun getSeriesSortState(): String? = secureStorage.read(KEY_SORT_SERIES)

    override suspend fun setSeriesSortState(persistenceKey: String) {
        secureStorage.save(KEY_SORT_SERIES, persistenceKey)
    }

    override suspend fun getAuthorsSortState(): String? = secureStorage.read(KEY_SORT_AUTHORS)

    override suspend fun setAuthorsSortState(persistenceKey: String) {
        secureStorage.save(KEY_SORT_AUTHORS, persistenceKey)
    }

    override suspend fun getNarratorsSortState(): String? = secureStorage.read(KEY_SORT_NARRATORS)

    override suspend fun setNarratorsSortState(persistenceKey: String) {
        secureStorage.save(KEY_SORT_NARRATORS, persistenceKey)
    }

    // Title sort article handling

    override suspend fun getIgnoreTitleArticles(): Boolean =
        secureStorage.read(KEY_IGNORE_TITLE_ARTICLES)?.toBooleanStrictOrNull() ?: true

    override suspend fun setIgnoreTitleArticles(ignore: Boolean) {
        secureStorage.save(KEY_IGNORE_TITLE_ARTICLES, ignore.toString())
    }

    // Series display preferences

    override suspend fun getHideSingleBookSeries(): Boolean =
        secureStorage.read(KEY_HIDE_SINGLE_BOOK_SERIES)?.toBooleanStrictOrNull() ?: true

    override suspend fun setHideSingleBookSeries(hide: Boolean) {
        secureStorage.save(KEY_HIDE_SINGLE_BOOK_SERIES, hide.toString())
    }

    // Playback preferences

    override suspend fun getSpatialPlayback(): Boolean =
        secureStorage.read(KEY_SPATIAL_PLAYBACK)?.toBooleanStrictOrNull() ?: true

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

    override suspend fun getDefaultPlaybackSpeed(): Float =
        secureStorage.read(KEY_DEFAULT_PLAYBACK_SPEED)?.toFloatOrNull() ?: DEFAULT_PLAYBACK_SPEED

    override suspend fun setDefaultPlaybackSpeed(speed: Float) {
        secureStorage.save(KEY_DEFAULT_PLAYBACK_SPEED, speed.toString())
        _preferenceChanges.emit(DomainPreferenceChangeEvent.PlaybackSpeedChanged(speed))
    }

    // Local preferences (device-specific, NOT synced)

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

    override suspend fun setThemeMode(mode: ThemeMode) {
        secureStorage.save(KEY_THEME_MODE, mode.toStorageString())
        _themeMode.value = mode
    }

    override suspend fun setDynamicColorsEnabled(enabled: Boolean) {
        secureStorage.save(KEY_DYNAMIC_COLORS, enabled.toString())
        _dynamicColorsEnabled.value = enabled
    }

    override suspend fun setAutoRewindEnabled(enabled: Boolean) {
        secureStorage.save(KEY_AUTO_REWIND, enabled.toString())
        _autoRewindEnabled.value = enabled
    }

    override suspend fun setWifiOnlyDownloads(enabled: Boolean) {
        secureStorage.save(KEY_WIFI_ONLY_DOWNLOADS, enabled.toString())
        _wifiOnlyDownloads.value = enabled
    }

    override suspend fun setAutoRemoveFinished(enabled: Boolean) {
        secureStorage.save(KEY_AUTO_REMOVE_FINISHED, enabled.toString())
        _autoRemoveFinished.value = enabled
    }

    override suspend fun setHapticFeedbackEnabled(enabled: Boolean) {
        secureStorage.save(KEY_HAPTIC_FEEDBACK, enabled.toString())
        _hapticFeedbackEnabled.value = enabled
    }
}
