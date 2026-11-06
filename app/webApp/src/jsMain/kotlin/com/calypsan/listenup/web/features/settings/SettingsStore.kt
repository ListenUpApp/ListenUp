package com.calypsan.listenup.web.features.settings

import com.calypsan.listenup.web.nav.RouteSession
import androidx.lifecycle.ViewModelStore
import com.calypsan.listenup.client.domain.model.ThemeMode
import com.calypsan.listenup.client.presentation.settings.HardcoverRowState
import com.calypsan.listenup.client.presentation.settings.SettingsUiState
import com.calypsan.listenup.client.presentation.settings.SettingsViewModel
import kotlinx.coroutines.flow.StateFlow
import com.calypsan.listenup.web.logging.RecentLogBuffer
import com.calypsan.listenup.web.logging.downloadRecentLogs
import org.koin.core.Koin

/**
 * An open Settings session: the state, the eight things web can actually change, and the teardown.
 *
 * [canCurateLibrary] offers Categories in the Library section, to those who may curate the library.
 *
 * [hardcoverRow] is the Account section's Hardcover entry — null hides it, because a server with
 * no Hardcover app has nothing to connect to, and an entry leading to "not set up" is a dead end.
 *
 * [downloadLogs] saves the browser's recent log (see
 * [com.calypsan.listenup.web.logging.RecentLogBuffer]) — null when this page has none to offer.
 *
 * Eight of the twelve `SettingsViewModel` offers; Ignore articles is the Library Sort menu's. The
 * other three — dynamic colours, Wi-Fi-only downloads, haptics — are omitted deliberately rather than disabled,
 * because a control that silently does nothing is worse than one that was never offered. A
 * browser cannot honour any of the three. Each omission is named at its section in [SettingsPage].
 *
 * The default sleep timer joined once the sleep sheet started from it.
 *
 * Default boost joined the list once the browser could act on it — see
 * [com.calypsan.listenup.web.playback.WebGainStage]. It was omitted while web had no gain stage,
 * which was the right call then and stopped being true.
 */
class SettingsSession(
    val state: StateFlow<SettingsUiState>,
    val hardcoverRow: StateFlow<HardcoverRowState?>,
    val canCurateLibrary: StateFlow<Boolean>,
    val onThemeMode: (ThemeMode) -> Unit,
    val onDefaultSpeed: (Float) -> Unit,
    val onDefaultBoost: (Float) -> Unit,
    val onSkipForward: (Int) -> Unit,
    val onSkipBackward: (Int) -> Unit,
    val onDefaultSleep: (Int?) -> Unit,
    val onAutoRewind: (Boolean) -> Unit,
    val onHideSingleBookSeries: (Boolean) -> Unit,
    override val close: () -> Unit,
    val downloadLogs: (() -> Unit)? = null,
) : RouteSession

/** How the page gets its session. */
typealias OpenSettings = () -> SettingsSession

/** The production source: the shared [SettingsViewModel] over the started graph. */
fun graphSettings(koin: Koin): OpenSettings =
    {
        val viewModel = koin.get<SettingsViewModel>()
        val store = ViewModelStore().apply { put(SETTINGS_STORE_KEY, viewModel) }
        SettingsSession(
            state = viewModel.state,
            hardcoverRow = viewModel.hardcoverRow,
            canCurateLibrary = viewModel.canCurateLibrary,
            onThemeMode = viewModel::setThemeMode,
            onDefaultSpeed = viewModel::setDefaultPlaybackSpeed,
            onDefaultBoost = viewModel::setDefaultVolumeBoostDb,
            onSkipForward = viewModel::setDefaultSkipForwardSec,
            onSkipBackward = viewModel::setDefaultSkipBackwardSec,
            onDefaultSleep = viewModel::setDefaultSleepTimerMin,
            onAutoRewind = viewModel::setAutoRewindEnabled,
            onHideSingleBookSeries = viewModel::setHideSingleBookSeries,
            close = store::clear,
            downloadLogs = koin.getOrNull<RecentLogBuffer>()?.let { buffer -> { downloadRecentLogs(buffer) } },
        )
    }

private const val SETTINGS_STORE_KEY = "settings"
