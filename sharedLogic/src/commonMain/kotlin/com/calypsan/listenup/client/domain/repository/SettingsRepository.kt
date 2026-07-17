package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.core.ServerUrl
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

    /**
     * Default skip-forward interval was changed.
     */
    data class SkipForwardChanged(
        val seconds: Int,
    ) : PreferenceChangeEvent

    /**
     * Default skip-backward interval was changed.
     */
    data class SkipBackwardChanged(
        val seconds: Int,
    ) : PreferenceChangeEvent
}

// region Segregated Interfaces (ISP)

/**
 * Persistence record for a registration awaiting admin approval.
 *
 * `userId` keys the server-side approval-status stream (SSE/polling);
 * `email` is shown on the pending-approval screen. No credentials are kept —
 * once approved the user retries `login()` normally.
 */
data class PendingRegistration(
    val userId: String,
    val email: String,
)

/**
 * Contract for authentication and session management.
 *
 * Used by components that need to manage auth tokens, session state,
 * or observe authentication changes (ApiClientFactory, navigation, token providers).
 */
interface AuthSession {
    /** Reactive authentication state. */
    val authState: StateFlow<AuthState>

    /**
     * Monotonic auth epoch (C8). Captured at the start of a token refresh and passed back to
     * [saveAuthTokens] as `ifEpoch`; a logout ([clearAuthTokens] / [clearSessionCredentials]) bumps
     * it, so an in-flight refresh completing AFTER logout can't resurrect the session.
     */
    suspend fun currentAuthEpoch(): Long

    /**
     * Save authentication tokens after successful login or token refresh.
     *
     * [ifEpoch] is the epoch captured by the refresh path via [currentAuthEpoch] at its start. When
     * non-null and no longer current (a logout intervened), the save is a no-op — this is the guard
     * that stops a late refresh from resurrecting a signed-out session (C8). Login/register/setup pass
     * null (unconditional). Writes land refresh → session → user → access so the access token — the
     * readiness signal a concurrent reader keys on — is never paired with a stale refresh token (C9).
     */
    suspend fun saveAuthTokens(
        access: AccessToken,
        refresh: RefreshToken,
        sessionId: String,
        userId: String,
        ifEpoch: Long? = null,
    )

    suspend fun getAccessToken(): AccessToken?

    suspend fun getRefreshToken(): RefreshToken?

    suspend fun getSessionId(): String?

    suspend fun getUserId(): String?

    /** Update only the access token (used during automatic token refresh). */
    suspend fun updateAccessToken(token: AccessToken)

    /** Clear authentication tokens (soft logout). */
    suspend fun clearAuthTokens()

    /**
     * Soft-expire the session: drop access token, refresh token, and session id but KEEP the
     * persisted user id, landing in [AuthState.SessionLapsed]. Used when the same server rejected
     * our credentials (expiry / dead refresh token) — the cached library's provenance is intact,
     * so the user must never be walled. Falls back to [clearAuthTokens] when no user id is
     * persisted. Contrast with [clearAuthTokens], the full wipe for deliberate sign-out,
     * account deletion, and server-instance change.
     */
    suspend fun clearSessionCredentials()

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
     * Get the pending registration on this device, or null when none is in flight.
     */
    suspend fun getPendingRegistration(): PendingRegistration?

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
 * (sync clients, DownloadWorker, ImageApi, API clients).
 */
interface ServerConfig {
    /**
     * Reactive change-signal for the active URL. Emits the current [getActiveUrl] value
     * after every URL mutation. The authoritative read is still [getActiveUrl]; observers
     * use this only to react to changes (e.g. invalidating cached connections).
     */
    val activeUrl: StateFlow<ServerUrl?>

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
     * Set the active URL explicitly (e.g. after a reachability probe picks the live address).
     * Persists it and publishes [activeUrl]. Pass the resolved local or remote URL.
     */
    suspend fun setActiveUrl(url: ServerUrl)

    /** Persist the stable mDNS instance id of the connected server (null clears it). Used to follow LAN IP changes. */
    suspend fun setConnectedServerId(id: String?)

    /** The stable mDNS instance id of the connected server, or null if none/manual. */
    suspend fun getConnectedServerId(): String?

    /** Refresh only the stored local (LAN) URL and publish [activeUrl] — no auth side effects. Used by IP-follow. */
    suspend fun updateLocalUrl(url: ServerUrl)

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
internal interface LibrarySync {
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

        /** Default skip-forward interval in seconds. */
        const val DEFAULT_SKIP_FORWARD_SEC = 30

        /** Default skip-backward interval in seconds. */
        const val DEFAULT_SKIP_BACKWARD_SEC = 10
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

    /**
     * Reactively observe the default skip-forward interval (seconds).
     *
     * Emits the current value on first collect, then re-emits whenever
     * [setDefaultSkipForwardSec] is called from any caller — so a Settings change
     * propagates live to the player without manual coordination.
     *
     * EM-R1: rethrows [CancellationException]; non-cancellation failures during
     * the initial read fall back to [DEFAULT_SKIP_FORWARD_SEC].
     */
    fun observeDefaultSkipForwardSec(): Flow<Int>

    /**
     * Reactively observe the default skip-backward interval (seconds).
     *
     * Emits the current value on first collect, then re-emits whenever
     * [setDefaultSkipBackwardSec] is called from any caller.
     *
     * EM-R1: rethrows [CancellationException]; non-cancellation failures during
     * the initial read fall back to [DEFAULT_SKIP_BACKWARD_SEC].
     */
    fun observeDefaultSkipBackwardSec(): Flow<Int>

    /**
     * Get the default skip-forward interval in seconds. Default is 30.
     */
    suspend fun getDefaultSkipForwardSec(): Int

    /**
     * Get the default skip-backward interval in seconds. Default is 10.
     */
    suspend fun getDefaultSkipBackwardSec(): Int

    /**
     * Set the default skip-forward interval (seconds). Persisted locally for
     * reactive reads; server sync is the caller's responsibility.
     */
    suspend fun setDefaultSkipForwardSec(seconds: Int)

    /**
     * Set the default skip-backward interval (seconds). Persisted locally for
     * reactive reads; server sync is the caller's responsibility.
     */
    suspend fun setDefaultSkipBackwardSec(seconds: Int)
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

    /** Set whether to only download on WiFi. */
    suspend fun setWifiOnlyDownloads(enabled: Boolean)

    // Controls

    /** Reactive haptic feedback preference. */
    val hapticFeedbackEnabled: StateFlow<Boolean>

    /** Set whether to enable haptic feedback on controls. */
    suspend fun setHapticFeedbackEnabled(enabled: Boolean)

    // Connection health (peer version + outdated-hint dismissal)

    /** The peer server's version observed on a response header / ServerInfo (null until first contact). */
    val peerServerVersion: StateFlow<String?>

    /** The peer server's API contract version observed alongside [peerServerVersion]. */
    val peerServerApi: StateFlow<String?>

    /** The (clientVersion, serverVersion) pair the user dismissed the Outdated hint for, if any. */
    val outdatedDismissedFor: StateFlow<Pair<String, String>?>

    /** Persist the peer server's version + API contract version. */
    suspend fun setPeerServerVersion(
        version: String,
        api: String,
    )

    /** Persist (or clear, when null) the dismissed (clientVersion, serverVersion) pair. */
    suspend fun setOutdatedDismissedFor(pair: Pair<String, String>?)

    /** Initialize local preferences from storage. Call on app startup. */
    suspend fun initializeLocalPreferences()
}

// endregion
