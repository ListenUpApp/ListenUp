package com.calypsan.listenup.client.data.repository

import io.github.oshai.kotlinlogging.KotlinLogging
import com.calypsan.listenup.core.SecureStorage
import com.calypsan.listenup.core.ServerUrl
import com.calypsan.listenup.client.domain.model.ThemeMode
import com.calypsan.listenup.client.domain.repository.AuthSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
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
internal class SettingsRepositoryImpl(
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
    override val preferenceChanges: SharedFlow<DomainPreferenceChangeEvent>
        field = MutableSharedFlow<DomainPreferenceChangeEvent>(extraBufferCapacity = 1)

    // Reactive change-signal for the active URL; authoritative read remains getActiveUrl().
    override val activeUrl: StateFlow<ServerUrl?>
        field = MutableStateFlow<ServerUrl?>(null)

    // Local preferences StateFlows (device-specific, NOT synced)
    override val themeMode: StateFlow<ThemeMode>
        field = MutableStateFlow(ThemeMode.SYSTEM)

    override val dynamicColorsEnabled: StateFlow<Boolean>
        field = MutableStateFlow(true)

    override val autoRewindEnabled: StateFlow<Boolean>
        field = MutableStateFlow(true)

    override val wifiOnlyDownloads: StateFlow<Boolean>
        field = MutableStateFlow(true)

    override val hapticFeedbackEnabled: StateFlow<Boolean>
        field = MutableStateFlow(true)

    override val peerServerVersion: StateFlow<String?>
        field = MutableStateFlow(null)

    override val peerServerApi: StateFlow<String?>
        field = MutableStateFlow(null)

    override val outdatedDismissedFor: StateFlow<Pair<String, String>?>
        field = MutableStateFlow(null)

    companion object {
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_REMOTE_URL = "server_remote_url"
        private const val KEY_ACTIVE_URL = "active_url"

        // Library identity (for detecting server reinstalls/resets)
        private const val KEY_CONNECTED_LIBRARY_ID = "connected_library_id"

        // Stable mDNS instance id of the connected server (for LAN IP-follow)
        private const val KEY_CONNECTED_SERVER_ID = "connected_server_id"

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
        private const val KEY_DEFAULT_PLAYBACK_SPEED = "default_playback_speed"
        private const val KEY_DEFAULT_SKIP_FORWARD_SEC = "default_skip_forward_sec"
        private const val KEY_DEFAULT_SKIP_BACKWARD_SEC = "default_skip_backward_sec"

        // Local preferences (device-specific, NOT synced)
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_DYNAMIC_COLORS = "dynamic_colors"
        private const val KEY_AUTO_REWIND = "auto_rewind"
        private const val KEY_WIFI_ONLY_DOWNLOADS = "wifi_only_downloads"
        private const val KEY_HAPTIC_FEEDBACK = "haptic_feedback"

        // Connection health (peer version + outdated-hint dismissal)
        private const val KEY_PEER_SERVER_VERSION = "peer_server_version"
        private const val KEY_PEER_SERVER_API = "peer_server_api"
        private const val KEY_OUTDATED_DISMISSED = "outdated_dismissed"

        // Default values
        const val DEFAULT_PLAYBACK_SPEED = 1.0f
        const val DEFAULT_SKIP_FORWARD_SEC = 30
        const val DEFAULT_SKIP_BACKWARD_SEC = 10
    }

    // Server configuration

    /** Recompute and publish the active URL after a mutation. Authoritative source is [getActiveUrl]. */
    private suspend fun publishActiveUrl() {
        activeUrl.value = getActiveUrl()
    }

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
        publishActiveUrl()
    }

    override suspend fun getServerUrl(): ServerUrl? = secureStorage.read(KEY_SERVER_URL)?.let { ServerUrl(it) }

    override suspend fun setRemoteUrl(url: String?) {
        if (url != null) {
            secureStorage.save(KEY_REMOTE_URL, url)
        } else {
            secureStorage.delete(KEY_REMOTE_URL)
        }
        publishActiveUrl()
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
        publishActiveUrl()
        return ServerUrl(fallback)
    }

    override suspend fun setActiveUrl(url: ServerUrl) {
        secureStorage.save(KEY_ACTIVE_URL, url.value)
        publishActiveUrl()
    }

    override suspend fun setConnectedServerId(id: String?) {
        if (id != null) {
            secureStorage.save(KEY_CONNECTED_SERVER_ID, id)
        } else {
            secureStorage.delete(KEY_CONNECTED_SERVER_ID)
        }
    }

    override suspend fun getConnectedServerId(): String? = secureStorage.read(KEY_CONNECTED_SERVER_ID)

    override suspend fun updateLocalUrl(url: ServerUrl) {
        secureStorage.save(KEY_SERVER_URL, url.value)
        publishActiveUrl()
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
        publishActiveUrl()
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
        secureStorage.delete(KEY_CONNECTED_SERVER_ID)
        authSession.initializeAuthState()
        publishActiveUrl()
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
        preferenceChanges.emit(DomainPreferenceChangeEvent.PlaybackSpeedChanged(speed))
    }

    // Skip intervals (synced across devices; persisted locally for reactive reads)

    override fun observeDefaultSkipForwardSec(): Flow<Int> =
        flow {
            val initial =
                try {
                    getDefaultSkipForwardSec()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn(e) { "Initial getDefaultSkipForwardSec failed; falling back to default." }
                    DomainPlaybackPreferences.DEFAULT_SKIP_FORWARD_SEC
                }
            emit(initial)

            preferenceChanges
                .filterIsInstance<DomainPreferenceChangeEvent.SkipForwardChanged>()
                .map { it.seconds }
                .collect { emit(it) }
        }

    override fun observeDefaultSkipBackwardSec(): Flow<Int> =
        flow {
            val initial =
                try {
                    getDefaultSkipBackwardSec()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn(e) { "Initial getDefaultSkipBackwardSec failed; falling back to default." }
                    DomainPlaybackPreferences.DEFAULT_SKIP_BACKWARD_SEC
                }
            emit(initial)

            preferenceChanges
                .filterIsInstance<DomainPreferenceChangeEvent.SkipBackwardChanged>()
                .map { it.seconds }
                .collect { emit(it) }
        }

    override suspend fun getDefaultSkipForwardSec(): Int =
        secureStorage.read(KEY_DEFAULT_SKIP_FORWARD_SEC)?.toIntOrNull() ?: DEFAULT_SKIP_FORWARD_SEC

    override suspend fun getDefaultSkipBackwardSec(): Int =
        secureStorage.read(KEY_DEFAULT_SKIP_BACKWARD_SEC)?.toIntOrNull() ?: DEFAULT_SKIP_BACKWARD_SEC

    override suspend fun setDefaultSkipForwardSec(seconds: Int) {
        secureStorage.save(KEY_DEFAULT_SKIP_FORWARD_SEC, seconds.toString())
        preferenceChanges.emit(DomainPreferenceChangeEvent.SkipForwardChanged(seconds))
    }

    override suspend fun setDefaultSkipBackwardSec(seconds: Int) {
        secureStorage.save(KEY_DEFAULT_SKIP_BACKWARD_SEC, seconds.toString())
        preferenceChanges.emit(DomainPreferenceChangeEvent.SkipBackwardChanged(seconds))
    }

    // Local preferences (device-specific, NOT synced)

    override suspend fun initializeLocalPreferences() {
        themeMode.value = ThemeMode.fromString(secureStorage.read(KEY_THEME_MODE))
        dynamicColorsEnabled.value =
            secureStorage.read(KEY_DYNAMIC_COLORS)?.toBooleanStrictOrNull() ?: true
        autoRewindEnabled.value =
            secureStorage.read(KEY_AUTO_REWIND)?.toBooleanStrictOrNull() ?: true
        wifiOnlyDownloads.value =
            secureStorage.read(KEY_WIFI_ONLY_DOWNLOADS)?.toBooleanStrictOrNull() ?: true
        hapticFeedbackEnabled.value =
            secureStorage.read(KEY_HAPTIC_FEEDBACK)?.toBooleanStrictOrNull() ?: true
        peerServerVersion.value = secureStorage.read(KEY_PEER_SERVER_VERSION)
        peerServerApi.value = secureStorage.read(KEY_PEER_SERVER_API)
        outdatedDismissedFor.value =
            secureStorage.read(KEY_OUTDATED_DISMISSED)?.let { raw ->
                if (raw.contains("|")) {
                    val (clientVersion, serverVersion) = raw.split("|", limit = 2)
                    clientVersion to serverVersion
                } else {
                    null
                }
            }
    }

    override suspend fun setThemeMode(mode: ThemeMode) {
        secureStorage.save(KEY_THEME_MODE, mode.toStorageString())
        themeMode.value = mode
    }

    override suspend fun setDynamicColorsEnabled(enabled: Boolean) {
        secureStorage.save(KEY_DYNAMIC_COLORS, enabled.toString())
        dynamicColorsEnabled.value = enabled
    }

    override suspend fun setAutoRewindEnabled(enabled: Boolean) {
        secureStorage.save(KEY_AUTO_REWIND, enabled.toString())
        autoRewindEnabled.value = enabled
    }

    override suspend fun setWifiOnlyDownloads(enabled: Boolean) {
        secureStorage.save(KEY_WIFI_ONLY_DOWNLOADS, enabled.toString())
        wifiOnlyDownloads.value = enabled
    }

    override suspend fun setHapticFeedbackEnabled(enabled: Boolean) {
        secureStorage.save(KEY_HAPTIC_FEEDBACK, enabled.toString())
        hapticFeedbackEnabled.value = enabled
    }

    override suspend fun setPeerServerVersion(
        version: String,
        api: String,
    ) {
        secureStorage.save(KEY_PEER_SERVER_VERSION, version)
        secureStorage.save(KEY_PEER_SERVER_API, api)
        peerServerVersion.value = version
        peerServerApi.value = api
    }

    override suspend fun setOutdatedDismissedFor(pair: Pair<String, String>?) {
        if (pair != null) {
            secureStorage.save(KEY_OUTDATED_DISMISSED, "${pair.first}|${pair.second}")
        } else {
            secureStorage.delete(KEY_OUTDATED_DISMISSED)
        }
        outdatedDismissedFor.value = pair
    }
}
