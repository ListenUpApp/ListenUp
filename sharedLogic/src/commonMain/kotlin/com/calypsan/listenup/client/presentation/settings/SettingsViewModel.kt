package com.calypsan.listenup.client.presentation.settings

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.onFailure
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.domain.model.ThemeMode
import com.calypsan.listenup.client.data.remote.RpcCacheInvalidator
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.PushRepository
import com.calypsan.listenup.client.domain.repository.SyncRepository
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import com.calypsan.listenup.client.domain.repository.LibraryPreferences
import com.calypsan.listenup.client.domain.repository.LocalPreferences
import com.calypsan.listenup.client.domain.repository.PlaybackPreferences
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.domain.repository.UserPreferencesRepository
import com.calypsan.listenup.core.error.ErrorBus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/**
 * Intermediate data class for type-safe combine of local display settings.
 * Used to avoid unsafe casts when combining more than 5 flows.
 */
private data class LocalDisplaySettings(
    val themeMode: ThemeMode,
    val dynamicColorsEnabled: Boolean,
    val autoRewindEnabled: Boolean,
    val wifiOnlyDownloads: Boolean,
    val autoRemoveFinished: Boolean,
)

/**
 * UI state for the Settings screen.
 *
 * Settings are divided into:
 * - Synced settings: Stored on server, follow user across devices
 * - Local settings: Device-specific, stored locally only
 */
data class SettingsUiState(
    // Loading state
    val isLoading: Boolean = true,
    val isSyncing: Boolean = false,
    val syncError: String? = null,
    // Synced settings (server storage)
    val defaultPlaybackSpeed: Float = PlaybackPreferences.DEFAULT_PLAYBACK_SPEED,
    val defaultSkipForwardSec: Int = 30,
    val defaultSkipBackwardSec: Int = 10,
    val defaultSleepTimerMin: Int? = null,
    val shakeToResetSleepTimer: Boolean = false,
    // Local settings (device storage) - populated from StateFlows
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColorsEnabled: Boolean = true,
    val autoRewindEnabled: Boolean = true,
    val wifiOnlyDownloads: Boolean = true,
    val autoRemoveFinished: Boolean = false,
    val hapticFeedbackEnabled: Boolean = true,
    // Library display settings (local)
    val ignoreTitleArticles: Boolean = true,
    val hideSingleBookSeries: Boolean = true,
    // Server info (read-only)
    val serverUrl: String? = null,
    val serverVersion: String? = null,
    val pushEnabled: Boolean = false,
)

/**
 * ViewModel for the Settings screen.
 *
 * Manages both synced settings (fetched from/pushed to server) and
 * local settings (device-specific preferences).
 *
 * Synced settings use optimistic updates: UI updates immediately,
 * server sync happens in background. Failures are logged but don't
 * revert local state.
 */
class SettingsViewModel(
    private val libraryPreferences: LibraryPreferences,
    private val playbackPreferences: PlaybackPreferences,
    private val localPreferences: LocalPreferences,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val instanceRepository: InstanceRepository,
    private val serverConfig: ServerConfig,
    private val authSession: AuthSession,
    private val syncRepository: SyncRepository,
    private val rpcCacheInvalidator: RpcCacheInvalidator,
    private val pushRepository: PushRepository,
    private val errorBus: ErrorBus,
) : ViewModel() {
    // Internal mutable state for settings that aren't reactive StateFlows
    private val internalState = MutableStateFlow(SettingsUiState())

    /**
     * Combined UI state that merges:
     * - Internal state (synced settings, loading states)
     * - Reactive local preferences from LocalPreferences
     *
     * Uses nested combine to stay within the 5-parameter type-safe overload.
     */
    val state: StateFlow<SettingsUiState> =
        combine(
            internalState,
            // Combine first group of local settings (5-param overload)
            combine(
                localPreferences.themeMode,
                localPreferences.dynamicColorsEnabled,
                localPreferences.autoRewindEnabled,
                localPreferences.wifiOnlyDownloads,
                localPreferences.autoRemoveFinished,
            ) { theme, dynamicColors, autoRewind, wifiOnly, autoRemove ->
                LocalDisplaySettings(theme, dynamicColors, autoRewind, wifiOnly, autoRemove)
            },
            localPreferences.hapticFeedbackEnabled,
            // Synced preferences from the Room-backed repository: a change made on another device
            // (or this one) lands here live, so the screen never needs a re-open to catch up.
            userPreferencesRepository.observePreferences(),
        ) { internal, localDisplay, haptics, synced ->
            internal.copy(
                defaultPlaybackSpeed = synced.defaultPlaybackSpeed,
                defaultSkipForwardSec = synced.defaultSkipForwardSec,
                defaultSkipBackwardSec = synced.defaultSkipBackwardSec,
                defaultSleepTimerMin = synced.defaultSleepTimerMin,
                shakeToResetSleepTimer = synced.shakeToResetSleepTimer,
                themeMode = localDisplay.themeMode,
                dynamicColorsEnabled = localDisplay.dynamicColorsEnabled,
                autoRewindEnabled = localDisplay.autoRewindEnabled,
                wifiOnlyDownloads = localDisplay.wifiOnlyDownloads,
                autoRemoveFinished = localDisplay.autoRemoveFinished,
                hapticFeedbackEnabled = haptics,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = SettingsUiState(),
        )

    init {
        loadSettings()
    }

    /**
     * Load all settings on initialization.
     * - Synced settings: Fetch from server, fall back to local cache
     * - Local settings: Read from secure storage
     * - Server info: Fetch from instance endpoint
     */
    private fun loadSettings() {
        viewModelScope.launch {
            // Load local settings that aren't reactive StateFlows. The synced playback fields
            // (speed/skip/sleep/shake) are sourced reactively from observePreferences() in the
            // combine above, so they are not seeded here.
            val ignoreTitleArticles = libraryPreferences.getIgnoreTitleArticles()
            val hideSingleBookSeries = libraryPreferences.getHideSingleBookSeries()

            // Load server URL from local storage
            val serverUrl = serverConfig.getServerUrl()?.value

            internalState.update {
                it.copy(
                    ignoreTitleArticles = ignoreTitleArticles,
                    hideSingleBookSeries = hideSingleBookSeries,
                    serverUrl = serverUrl,
                )
            }

            // Fetch synced settings and server info from server
            fetchSyncedSettings()
            fetchServerInfo()
        }
    }

    /**
     * Fetch server instance info to get version.
     */
    private suspend fun fetchServerInfo() {
        when (val result = instanceRepository.getServerInfo()) {
            is AppResult.Success -> {
                internalState.update {
                    it.copy(serverVersion = result.data.version, pushEnabled = result.data.pushEnabled)
                }
            }

            is AppResult.Failure -> {
                logger.warn { "Failed to fetch server info: ${result.message}" }
            }
        }
    }

    /**
     * Refresh synced settings from the server. The repository writes the result through to its Room
     * cache, which the `observePreferences()` flow folded into [state] picks up reactively — so this
     * method only triggers the fetch and toggles the loading flags. On failure the cached values
     * already in [state] stay put (offline-first); we just clear the spinner.
     */
    private suspend fun fetchSyncedSettings() {
        internalState.update { it.copy(isSyncing = true, syncError = null) }

        when (val result = userPreferencesRepository.getPreferences()) {
            is AppResult.Success -> {
                // Mirror into the legacy reactive player store too, so a cross-device skip/speed
                // change reaches the player (which observes PlaybackPreferences, not the Settings VM).
                val prefs = result.data
                playbackPreferences.setDefaultPlaybackSpeed(prefs.defaultPlaybackSpeed)
                playbackPreferences.setDefaultSkipForwardSec(prefs.defaultSkipForwardSec)
                playbackPreferences.setDefaultSkipBackwardSec(prefs.defaultSkipBackwardSec)
                internalState.update { it.copy(isLoading = false, isSyncing = false) }
            }

            is AppResult.Failure -> {
                logger.warn { "Failed to fetch synced settings: ${result.message}" }
                internalState.update { it.copy(isLoading = false, isSyncing = false) }
            }
        }
    }

    // region Synced Settings (server storage)

    /**
     * Set the default playback speed for new books.
     * Updates locally immediately (optimistic), syncs to server in background.
     */
    fun setDefaultPlaybackSpeed(speed: Float) {
        viewModelScope.launch {
            // Mirror into the player's reactive store (it observes PlaybackPreferences, not this VM).
            playbackPreferences.setDefaultPlaybackSpeed(speed)
            // The repository writes Room optimistically (driving observePreferences() → state) then
            // pushes to the server — so the UI reflects the change instantly without a second copy here.
            userPreferencesRepository
                .setDefaultPlaybackSpeed(speed)
                .onFailure { logger.warn { "Failed to sync default playback speed to server: ${it.message}" } }
        }
    }

    /**
     * Set the default skip forward duration.
     * Updates locally immediately (optimistic), syncs to server in background.
     */
    fun setDefaultSkipForwardSec(seconds: Int) {
        viewModelScope.launch {
            // Persist to the player's reactive store (the live source the player observes); the
            // repository's optimistic Room write drives the Settings UI via observePreferences().
            playbackPreferences.setDefaultSkipForwardSec(seconds)
            userPreferencesRepository
                .setDefaultSkipForwardSec(seconds)
                .onFailure { logger.warn { "Failed to sync default skip-forward to server: ${it.message}" } }
        }
    }

    /**
     * Set the default skip backward duration.
     * Updates locally immediately (optimistic), syncs to server in background.
     */
    fun setDefaultSkipBackwardSec(seconds: Int) {
        viewModelScope.launch {
            // Persist to the player's reactive store (the live source the player observes); the
            // repository's optimistic Room write drives the Settings UI via observePreferences().
            playbackPreferences.setDefaultSkipBackwardSec(seconds)
            userPreferencesRepository
                .setDefaultSkipBackwardSec(seconds)
                .onFailure { logger.warn { "Failed to sync default skip-backward to server: ${it.message}" } }
        }
    }

    /**
     * Set the default sleep timer duration.
     * Pass null to disable the default sleep timer.
     */
    fun setDefaultSleepTimerMin(minutes: Int?) {
        viewModelScope.launch {
            // The repository's optimistic Room write drives the UI via observePreferences().
            userPreferencesRepository
                .setDefaultSleepTimerMin(minutes)
                .onFailure { logger.warn { "Failed to sync default sleep-timer to server: ${it.message}" } }
        }
    }

    /**
     * Set whether shaking the device resets the sleep timer.
     */
    fun setShakeToResetSleepTimer(enabled: Boolean) {
        viewModelScope.launch {
            // The repository's optimistic Room write drives the UI via observePreferences().
            userPreferencesRepository
                .setShakeToResetSleepTimer(enabled)
                .onFailure { logger.warn { "Failed to sync shake-to-reset-sleep-timer to server: ${it.message}" } }
        }
    }

    // endregion

    // region Local Settings (device storage)

    /**
     * Set the theme mode (system/light/dark).
     * Device-local setting, does not sync to server.
     */
    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            localPreferences.setThemeMode(mode)
            // StateFlow update handled by combine
        }
    }

    /**
     * Set whether to use dynamic (wallpaper-based) colors.
     * Device-local setting, does not sync to server.
     */
    fun setDynamicColorsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            localPreferences.setDynamicColorsEnabled(enabled)
        }
    }

    /**
     * Set whether to auto-rewind when resuming playback.
     * Device-local setting, does not sync to server.
     */
    fun setAutoRewindEnabled(enabled: Boolean) {
        viewModelScope.launch {
            localPreferences.setAutoRewindEnabled(enabled)
        }
    }

    /**
     * Set whether to only download on WiFi.
     * Device-local setting, does not sync to server.
     */
    fun setWifiOnlyDownloads(enabled: Boolean) {
        viewModelScope.launch {
            localPreferences.setWifiOnlyDownloads(enabled)
        }
    }

    /**
     * Set whether to auto-remove downloads after finishing.
     * Device-local setting, does not sync to server.
     */
    fun setAutoRemoveFinished(enabled: Boolean) {
        viewModelScope.launch {
            localPreferences.setAutoRemoveFinished(enabled)
        }
    }

    /**
     * Set whether haptic feedback is enabled.
     * Device-local setting, does not sync to server.
     */
    fun setHapticFeedbackEnabled(enabled: Boolean) {
        viewModelScope.launch {
            localPreferences.setHapticFeedbackEnabled(enabled)
        }
    }

    /**
     * Set whether to ignore leading articles when sorting titles.
     * Device-local setting for display preference.
     */
    fun setIgnoreTitleArticles(ignore: Boolean) {
        viewModelScope.launch {
            libraryPreferences.setIgnoreTitleArticles(ignore)
            internalState.update { it.copy(ignoreTitleArticles = ignore) }
        }
    }

    /**
     * Set whether to hide series with only one book.
     * Device-local setting for display preference.
     */
    fun setHideSingleBookSeries(hide: Boolean) {
        viewModelScope.launch {
            libraryPreferences.setHideSingleBookSeries(hide)
            internalState.update { it.copy(hideSingleBookSeries = hide) }
        }
    }

    // endregion

    // region Account Actions

    /**
     * Sign out the current user.
     *
     * Stops real-time sync first — otherwise the engine keeps reconnecting against
     * the now-unauthenticated endpoint — then clears tokens and returns to login.
     */
    fun signOut() {
        viewModelScope.launch {
            syncRepository.disconnect()
            rpcCacheInvalidator.invalidateAll()
            authSession.clearAuthTokens()
        }
    }

    // endregion

    /**
     * Sends a test push notification to this user's own registered devices. Success has no UI
     * feedback — the arriving notification IS the feedback (and only shows while the app is
     * backgrounded, since foreground pushes are suppressed in favor of the live SSE surface).
     * Failure routes through the global error bus.
     */
    fun sendTestNotification() {
        viewModelScope.launch {
            pushRepository.sendTestNotification().onFailure { errorBus.emit(it) }
        }
    }
}
