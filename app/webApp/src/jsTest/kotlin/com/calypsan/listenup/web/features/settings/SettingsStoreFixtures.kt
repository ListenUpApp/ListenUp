package com.calypsan.listenup.web.features.settings

import com.calypsan.listenup.client.domain.model.ThemeMode
import com.calypsan.listenup.client.presentation.settings.HardcoverRowState
import com.calypsan.listenup.client.presentation.settings.SettingsUiState
import kotlinx.coroutines.flow.MutableStateFlow

/** A session over a state that never changes — the shape specs pass in place of the graph. */
fun fixedSettings(
    state: SettingsUiState = SettingsUiState(),
    onThemeMode: (ThemeMode) -> Unit = {},
    onDefaultSpeed: (Float) -> Unit = {},
    onDefaultBoost: (Float) -> Unit = {},
    onSkipForward: (Int) -> Unit = {},
    onSkipBackward: (Int) -> Unit = {},
    onAutoRewind: (Boolean) -> Unit = {},
    onHideSingleBookSeries: (Boolean) -> Unit = {},
    hardcoverRow: HardcoverRowState? = null,
    canCurateLibrary: Boolean = false,
): OpenSettings =
    {
        SettingsSession(
            state = MutableStateFlow(state),
            hardcoverRow = MutableStateFlow(hardcoverRow),
            canCurateLibrary = MutableStateFlow(canCurateLibrary),
            onThemeMode = onThemeMode,
            onDefaultSpeed = onDefaultSpeed,
            onDefaultBoost = onDefaultBoost,
            onSkipForward = onSkipForward,
            onSkipBackward = onSkipBackward,
            onAutoRewind = onAutoRewind,
            onHideSingleBookSeries = onHideSingleBookSeries,
            close = {},
        )
    }
