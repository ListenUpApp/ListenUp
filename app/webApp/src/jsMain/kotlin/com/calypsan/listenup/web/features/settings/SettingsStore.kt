package com.calypsan.listenup.web.features.settings

import androidx.lifecycle.ViewModelStore
import com.calypsan.listenup.client.domain.model.ThemeMode
import com.calypsan.listenup.client.presentation.settings.SettingsUiState
import com.calypsan.listenup.client.presentation.settings.SettingsViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.Koin

/**
 * An open Settings session: the state, the seven things web can actually change, and the teardown.
 *
 * Seven of the twelve `SettingsViewModel` offers. The other five — dynamic colours, Wi-Fi-only
 * downloads, haptics, volume boost, the sleep-timer default — are omitted deliberately rather than
 * disabled, because a control that silently does nothing is worse than one that was never offered.
 * Four a browser cannot honour at all; the fifth no client honours yet. Each omission is named at
 * its section in [SettingsPage].
 */
class SettingsSession(
    val state: StateFlow<SettingsUiState>,
    val onThemeMode: (ThemeMode) -> Unit,
    val onDefaultSpeed: (Float) -> Unit,
    val onSkipForward: (Int) -> Unit,
    val onSkipBackward: (Int) -> Unit,
    val onAutoRewind: (Boolean) -> Unit,
    val onIgnoreTitleArticles: (Boolean) -> Unit,
    val onHideSingleBookSeries: (Boolean) -> Unit,
    val close: () -> Unit,
)

/** How the page gets its session. */
typealias OpenSettings = () -> SettingsSession

/** The production source: the shared [SettingsViewModel] over the started graph. */
fun graphSettings(koin: Koin): OpenSettings =
    {
        val viewModel = koin.get<SettingsViewModel>()
        val store = ViewModelStore().apply { put(SETTINGS_STORE_KEY, viewModel) }
        SettingsSession(
            state = viewModel.state,
            onThemeMode = viewModel::setThemeMode,
            onDefaultSpeed = viewModel::setDefaultPlaybackSpeed,
            onSkipForward = viewModel::setDefaultSkipForwardSec,
            onSkipBackward = viewModel::setDefaultSkipBackwardSec,
            onAutoRewind = viewModel::setAutoRewindEnabled,
            onIgnoreTitleArticles = viewModel::setIgnoreTitleArticles,
            onHideSingleBookSeries = viewModel::setHideSingleBookSeries,
            close = store::clear,
        )
    }

/** A session over a state that never changes — the shape specs pass in place of the graph. */
fun fixedSettings(
    state: SettingsUiState = SettingsUiState(),
    onThemeMode: (ThemeMode) -> Unit = {},
    onDefaultSpeed: (Float) -> Unit = {},
    onSkipForward: (Int) -> Unit = {},
    onSkipBackward: (Int) -> Unit = {},
    onAutoRewind: (Boolean) -> Unit = {},
    onIgnoreTitleArticles: (Boolean) -> Unit = {},
    onHideSingleBookSeries: (Boolean) -> Unit = {},
): OpenSettings =
    {
        SettingsSession(
            state = MutableStateFlow(state),
            onThemeMode = onThemeMode,
            onDefaultSpeed = onDefaultSpeed,
            onSkipForward = onSkipForward,
            onSkipBackward = onSkipBackward,
            onAutoRewind = onAutoRewind,
            onIgnoreTitleArticles = onIgnoreTitleArticles,
            onHideSingleBookSeries = onHideSingleBookSeries,
            close = {},
        )
    }

private const val SETTINGS_STORE_KEY = "settings"
