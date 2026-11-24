package com.calypsan.listenup.web.features.settings

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.domain.model.ThemeMode
import com.calypsan.listenup.client.presentation.settings.HardcoverRowState
import com.calypsan.listenup.client.presentation.settings.SettingsUiState
import kotlinx.coroutines.flow.MutableStateFlow

/** Renders [SettingsPage] with no-op callback defaults for specs. */
@Composable
@Suppress("LongParameterList")
internal fun testSettingsPage(
    state: SettingsUiState,
    onThemeMode: (ThemeMode) -> Unit = {},
    onDefaultSpeed: (Float) -> Unit = {},
    onDefaultBoost: (Float) -> Unit = {},
    onSkipForward: (Int) -> Unit = {},
    onSkipBackward: (Int) -> Unit = {},
    onDefaultSleep: (Int?) -> Unit = {},
    onAutoRewind: (Boolean) -> Unit = {},
    onHideSingleBookSeries: (Boolean) -> Unit = {},
    onOpenDevices: () -> Unit = {},
    onOpenNotifications: () -> Unit = {},
    onOpenLicences: () -> Unit = {},
    hardcoverRow: HardcoverRowState? = null,
    onOpenHardcover: () -> Unit = {},
    canCurateLibrary: Boolean = false,
    onOpenCategories: () -> Unit = {},
    onDownloadLogs: (() -> Unit)? = null,
) {
    SettingsPage(
        state = state,
        onThemeMode = onThemeMode,
        onDefaultSpeed = onDefaultSpeed,
        onDefaultBoost = onDefaultBoost,
        onSkipForward = onSkipForward,
        onSkipBackward = onSkipBackward,
        onDefaultSleep = onDefaultSleep,
        onAutoRewind = onAutoRewind,
        onHideSingleBookSeries = onHideSingleBookSeries,
        onOpenDevices = onOpenDevices,
        onOpenNotifications = onOpenNotifications,
        onOpenLicences = onOpenLicences,
        hardcoverRow = hardcoverRow,
        onOpenHardcover = onOpenHardcover,
        canCurateLibrary = canCurateLibrary,
        onOpenCategories = onOpenCategories,
        onDownloadLogs = onDownloadLogs,
    )
}

/** A session over a state that never changes — the shape specs pass in place of the graph. */
fun fixedSettings(
    state: SettingsUiState = SettingsUiState(),
    onThemeMode: (ThemeMode) -> Unit = {},
    onDefaultSpeed: (Float) -> Unit = {},
    onDefaultBoost: (Float) -> Unit = {},
    onSkipForward: (Int) -> Unit = {},
    onSkipBackward: (Int) -> Unit = {},
    onDefaultSleep: (Int?) -> Unit = {},
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
            onDefaultSleep = onDefaultSleep,
            onAutoRewind = onAutoRewind,
            onHideSingleBookSeries = onHideSingleBookSeries,
            close = {},
        )
    }
