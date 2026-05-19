package com.calypsan.listenup.client.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.core.Failure
import com.calypsan.listenup.core.Success
import com.calypsan.listenup.client.domain.model.ThemeMode
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import com.calypsan.listenup.client.domain.repository.LibraryPreferences
import com.calypsan.listenup.client.domain.repository.LocalPreferences
import com.calypsan.listenup.client.domain.repository.PlaybackPreferences
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.domain.repository.UserPreferencesRepository
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
    val spatialPlayback: Boolean = true,
    val ignoreTitleArticles: Boolean = true,
    val hideSingleBookSeries: Boolean = true,
    // Server info (read-only)
    val serverUrl: String? = null,
    val serverVersion: String? = null,
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
        ) { internal, localDisplay, haptics ->
            internal.copy(
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
            // Load local settings that aren't reactive StateFlows
            val spatialPlayback = playbackPreferences.getSpatialPlayback()
            val ignoreTitleArticles = libraryPreferences.getIgnoreTitleArticles()
            val hideSingleBookSeries = libraryPreferences.getHideSingleBookSeries()
            val defaultPlaybackSpeed = playbackPreferences.getDefaultPlaybackSpeed()

            // Load server URL from local storage
            val serverUrl = serverConfig.getServerUrl()?.value

            internalState.update {
                it.copy(
                    spatialPlayback = spatialPlayback,
                    ignoreTitleArticles = ignoreTitleArticles,
                    hideSingleBookSeries = hideSingleBookSeries,
                    defaultPlaybackSpeed = defaultPlaybackSpeed,
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
        when (val result = instanceRepository.getInstance()) {
            is Success -> {
                internalState.update {
                    it.copy(serverVersion = result.data.version)
                }
            }

            is Failure -> {
                logger.warn { "Failed to fetch server info: ${result.message}" }
            }
        }
    }

    /**
     * Fetch synced settings from server and update local cache.
     * Falls back to cached values if server is unreachable.
     */
    private suspend fun fetchSyncedSettings() {
        internalState.update { it.copy(isSyncing = true, syncError = null) }

        when (val result = userPreferencesRepository.getPreferences()) {
            is Success -> {
                val prefs = result.data
                internalState.update {
                    it.copy(
                        defaultPlaybackSpeed = prefs.defaultPlaybackSpeed,
                        defaultSkipForwardSec = prefs.defaultSkipForwardSec,
                        defaultSkipBackwardSec = prefs.defaultSkipBackwardSec,
                        defaultSleepTimerMin = prefs.defaultSleepTimerMin,
                        shakeToResetSleepTimer = prefs.shakeToResetSleepTimer,
                        isLoading = false,
                        isSyncing = false,
                    )
                }

                // Update local cache for offline access
                playbackPreferences.setDefaultPlaybackSpeed(prefs.defaultPlaybackSpeed)
            }

            is Failure -> {
                logger.warn { "Failed to fetch synced settings: ${result.message}" }
                internalState.update {
                    it.copy(
                        isLoading = false,
                        isSyncing = false,
                        // Keep cached values, just note the sync failed
                    )
                }
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
            // Update local cache immediately (optimistic)
            playbackPreferences.setDefaultPlaybackSpeed(speed)
            internalState.update { it.copy(defaultPlaybackSpeed = speed) }

            // Sync to server in background
            userPreferencesRepository.setDefaultPlaybackSpeed(speed)
        }
    }

    /**
     * Set the default skip forward duration.
     * Updates locally immediately (optimistic), syncs to server in background.
     */
    fun setDefaultSkipForwardSec(seconds: Int) {
        viewModelScope.launch {
            internalState.update { it.copy(defaultSkipForwardSec = seconds) }
            userPreferencesRepository.setDefaultSkipForwardSec(seconds)
        }
    }

    /**
     * Set the default skip backward duration.
     * Updates locally immediately (optimistic), syncs to server in background.
     */
    fun setDefaultSkipBackwardSec(seconds: Int) {
        viewModelScope.launch {
            internalState.update { it.copy(defaultSkipBackwardSec = seconds) }
            userPreferencesRepository.setDefaultSkipBackwardSec(seconds)
        }
    }

    /**
     * Set the default sleep timer duration.
     * Pass null to disable the default sleep timer.
     */
    fun setDefaultSleepTimerMin(minutes: Int?) {
        viewModelScope.launch {
            internalState.update { it.copy(defaultSleepTimerMin = minutes) }
            userPreferencesRepository.setDefaultSleepTimerMin(minutes)
        }
    }

    /**
     * Set whether shaking the device resets the sleep timer.
     */
    fun setShakeToResetSleepTimer(enabled: Boolean) {
        viewModelScope.launch {
            internalState.update { it.copy(shakeToResetSleepTimer = enabled) }
            userPreferencesRepository.setShakeToResetSleepTimer(enabled)
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
     * Set spatial (5.1 surround) audio preference.
     * Device-local setting for audio hardware capability.
     */
    fun setSpatialPlayback(enabled: Boolean) {
        viewModelScope.launch {
            playbackPreferences.setSpatialPlayback(enabled)
            internalState.update { it.copy(spatialPlayback = enabled) }
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
     * Clears authentication tokens and returns to login screen.
     */
    fun signOut() {
        viewModelScope.launch {
            authSession.clearAuthTokens()
        }
    }

    // endregion
}
