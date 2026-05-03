package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.client.core.ServerUrl
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Events emitted when user preferences change.
 * Observed by the sync layer to queue operations without creating circular dependencies.
 */
sealed interface PreferenceChangeEvent {
    /**
     * Default playback speed was changed.
     */
    data class PlaybackSpeedChanged(
        val speed: Float,
    ) : PreferenceChangeEvent
}

// region Segregated Interfaces (ISP)

/**
 * Contract for authentication and session management.
 *
 * Used by components that need to manage auth tokens, session state,
 * or observe authentication changes (ApiClientFactory, navigation, token providers).
 */
interface AuthSession {
    /** Reactive authentication state. */
    val authState: StateFlow<AuthState>

    /** Save authentication tokens after successful login or token refresh. */
    suspend fun saveAuthTokens(
        access: AccessToken,
        refresh: RefreshToken,
        sessionId: String,
        userId: String,
    )

    suspend fun getAccessToken(): AccessToken?

    suspend fun getRefreshToken(): RefreshToken?

    suspend fun getSessionId(): String?

    suspend fun getUserId(): String?

    /** Update only the access token (used during automatic token refresh). */
    suspend fun updateAccessToken(token: AccessToken)

    /** Clear authentication tokens (soft logout). */
    suspend fun clearAuthTokens()

    /** Check if user has stored authentication tokens. */
    suspend fun isAuthenticated(): Boolean

    /** Initialize authentication state on app startup. */
    suspend fun initializeAuthState()

    /** Check server status to determine if setup is required. */
    suspend fun checkServerStatus(): AuthState

    /**
     * Refresh the open registration status from the server.
     * Updates the NeedsLogin state with the latest value without showing loading state.
     * Call this when entering the login screen to ensure the "Create Account" link is shown.
     */
    suspend fun refreshOpenRegistration()

    /**
     * Persist that this device has a registration awaiting admin approval.
     *
     * `userId` keys the server-side approval-status stream (SSE/polling);
     * `email` is shown on the pending-approval screen. No credentials are
     * stored — once approved, the user logs in normally.
     */
    suspend fun savePendingRegistration(
        userId: String,
        email: String,
    )

    /**
     * Get the (userId, email) of any pending registration on this device,
     * or null when no registration is in flight.
     */
    suspend fun getPendingRegistration(): Pair<String, String>?

    /**
     * Clear pending registration state.
     * Called after the user logs in, cancels, or is denied.
     */
    suspend fun clearPendingRegistration()
}

/**
 * Contract for server URL configuration.
 *
 * Used by components that need to know the server URL
 * (SSEManager, DownloadWorker, ImageApi, API clients).
 */
interface ServerConfig {
    suspend fun setServerUrl(url: ServerUrl)

    suspend fun getServerUrl(): ServerUrl?

    suspend fun hasServerConfigured(): Boolean

    /** Set the remote URL learned from instance API or mDNS discovery. */
    suspend fun setRemoteUrl(url: String?)

    /** Get the stored remote URL. */
    suspend fun getRemoteUrl(): ServerUrl?

    /**
     * Get the currently active URL for API requests.
     * Defaults to localUrl, falls back to remoteUrl on network failure.
     */
    suspend fun getActiveUrl(): ServerUrl?

    /**
     * Switch the active URL (called on network failure to try the other URL).
     * Returns the new active URL, or null if no fallback is available.
     */
    suspend fun switchToFallbackUrl(): ServerUrl?

    /**
     * Reset active URL to prefer local (called on app foreground/resume).
     */
    suspend fun preferLocalUrl()

    /** Disconnect from current server (clears URL and auth data). */
    suspend fun disconnectFromServer()

    /** Clear all settings including server URL (complete reset). */
    suspend fun clearAll()
}

/**
 * Contract for library identity and sync verification.
 *
 * Used by SyncManager to detect when the server's library has changed
 * (e.g., server reinstalled, database wiped) and trigger appropriate
 * resync flows.
 */
interface LibrarySync {
    /**
     * Get the library ID this client is currently synced with.
     * Returns null if this is the first sync (no library connected yet).
     */
    suspend fun getConnectedLibraryId(): String?

    /**
     * Store the library ID after successful sync verification.
     * This becomes the reference point for future mismatch detection.
     */
    suspend fun setConnectedLibraryId(libraryId: String)

    /**
     * Clear the connected library ID.
     * Called when switching servers or after detecting a mismatch.
     */
    suspend fun clearConnectedLibraryId()
}

/**
 * Contract for library display and sort preferences.
 *
 * Used by LibraryViewModel and SettingsViewModel for managing
 * how books, series, and contributors are displayed and sorted.
 */
interface LibraryPreferences {
    // Sort state per tab
    suspend fun getBooksSortState(): String?

    suspend fun setBooksSortState(persistenceKey: String)

    suspend fun getSeriesSortState(): String?

    suspend fun setSeriesSortState(persistenceKey: String)

    suspend fun getAuthorsSortState(): String?

    suspend fun setAuthorsSortState(persistenceKey: String)

    suspend fun getNarratorsSortState(): String?

    suspend fun setNarratorsSortState(persistenceKey: String)

    // Display options
    suspend fun getIgnoreTitleArticles(): Boolean

    suspend fun setIgnoreTitleArticles(ignore: Boolean)

    suspend fun getHideSingleBookSeries(): Boolean

    suspend fun setHideSingleBookSeries(hide: Boolean)
}

/**
 * Contract for playback preferences.
 *
 * Used by SettingsViewModel, NowPlayingViewModel, DownloadWorker,
 * and SyncManager for managing playback-related settings.
 */
interface PlaybackPreferences {
    companion object {
        /** Default playback speed for new books (1.0x = normal speed). */
        const val DEFAULT_PLAYBACK_SPEED = 1.0f
    }

    /** Flow of preference change events for sync layer. */
    val preferenceChanges: SharedFlow<PreferenceChangeEvent>

    /**
     * Reactively observe the default playback speed.
     *
     * Emits the current value on first collect, then re-emits whenever
     * [setDefaultPlaybackSpeed] is called from any caller. Use this in
     * ViewModels' combine chains so a Settings change propagates to the
     * now-playing surface without manual coordination.
     *
     * EM-R1: rethrows [CancellationException]; non-cancellation failures
     * during the initial read fall back to [DEFAULT_PLAYBACK_SPEED].
     */
    fun observeDefaultPlaybackSpeed(): Flow<Float>

    /**
     * Get the default playback speed for new books.
     * @return Playback speed multiplier (e.g., 1.0, 1.25, 1.5). Default is 1.0.
     */
    suspend fun getDefaultPlaybackSpeed(): Float

    /**
     * Set the default playback speed for new books.
     * This is a synced setting - will be pushed to server.
     */
    suspend fun setDefaultPlaybackSpeed(speed: Float)

    /** Get whether spatial (5.1 surround) audio is preferred. */
    suspend fun getSpatialPlayback(): Boolean

    /** Set spatial audio preference (per-device setting). */
    suspend fun setSpatialPlayback(enabled: Boolean)
}

/**
 * Contract for local device preferences.
 *
 * These settings do NOT sync to the server - they're device-specific.
 * Examples: theme, dynamic colors, haptics, download behavior.
 */
interface LocalPreferences {
    // Appearance

    /** Reactive theme mode preference. */
    val themeMode: StateFlow<ThemeMode>

    /** Reactive dynamic colors preference (Material You). */
    val dynamicColorsEnabled: StateFlow<Boolean>

    /** Set the theme mode (system/light/dark). */
    suspend fun setThemeMode(mode: ThemeMode)

    /** Set whether to use dynamic (wallpaper-based) colors. Android 12+ only. */
    suspend fun setDynamicColorsEnabled(enabled: Boolean)

    // Playback (local settings)

    /** Reactive auto-rewind preference. */
    val autoRewindEnabled: StateFlow<Boolean>

    /** Set whether to auto-rewind when resuming playback. */
    suspend fun setAutoRewindEnabled(enabled: Boolean)

    // Downloads

    /** Reactive WiFi-only downloads preference. */
    val wifiOnlyDownloads: StateFlow<Boolean>

    /** Reactive auto-remove finished downloads preference. */
    val autoRemoveFinished: StateFlow<Boolean>

    /** Set whether to only download on WiFi. */
    suspend fun setWifiOnlyDownloads(enabled: Boolean)

    /** Set whether to auto-remove downloads after finishing a book. */
    suspend fun setAutoRemoveFinished(enabled: Boolean)

    // Controls

    /** Reactive haptic feedback preference. */
    val hapticFeedbackEnabled: StateFlow<Boolean>

    /** Set whether to enable haptic feedback on controls. */
    suspend fun setHapticFeedbackEnabled(enabled: Boolean)

    /** Initialize local preferences from storage. Call on app startup. */
    suspend fun initializeLocalPreferences()
}

// endregion
