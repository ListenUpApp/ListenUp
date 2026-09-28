package com.calypsan.listenup.client.features.settings

import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import com.calypsan.listenup.client.design.components.ListenUpTopAppBar
import com.calypsan.listenup.client.presentation.settings.SettingsEvent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FilterNone
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.calypsan.listenup.client.design.components.ListenUpScaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.design.components.SectionColumns
import com.calypsan.listenup.client.design.components.SectionGroup
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.window.core.layout.WindowSizeClass
import com.calypsan.listenup.client.design.components.SettingNavigationRow
import com.calypsan.listenup.client.design.components.SettingRow
import com.calypsan.listenup.client.design.components.SettingToggleRow
import com.calypsan.listenup.client.design.components.SignOutConfirmDialog
import com.calypsan.listenup.client.design.components.ValuePill
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.domain.model.ThemeMode
import com.calypsan.listenup.client.features.nowplaying.VolumeBoostPresets
import com.calypsan.listenup.client.presentation.settings.SettingsUiState
import com.calypsan.listenup.client.presentation.settings.SettingsViewModel
import kotlin.math.roundToInt
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_about
import listenup.composeapp.generated.resources.common_account
import listenup.composeapp.generated.resources.common_library
import listenup.composeapp.generated.resources.common_playback
import listenup.composeapp.generated.resources.common_server
import listenup.composeapp.generated.resources.common_settings
import listenup.composeapp.generated.resources.common_sign_out
import listenup.composeapp.generated.resources.common_storage
import listenup.composeapp.generated.resources.common_theme
import listenup.composeapp.generated.resources.devices_manage_active_sessions
import listenup.composeapp.generated.resources.player_boost_db
import listenup.composeapp.generated.resources.player_boost_off
import listenup.composeapp.generated.resources.settings_app_version
import listenup.composeapp.generated.resources.settings_appearance
import listenup.composeapp.generated.resources.settings_autorewind_on_resume
import listenup.composeapp.generated.resources.settings_autostart_sleep_timer_when_playing
import listenup.composeapp.generated.resources.settings_boost_used_for_new_books
import listenup.composeapp.generated.resources.settings_choose_light_dark_or_follow
import listenup.composeapp.generated.resources.settings_default_boost
import listenup.composeapp.generated.resources.settings_default_speed
import listenup.composeapp.generated.resources.settings_default_timer
import listenup.composeapp.generated.resources.settings_devices
import listenup.composeapp.generated.resources.settings_downloads
import listenup.composeapp.generated.resources.settings_duration_when_pressing_skip_backward
import listenup.composeapp.generated.resources.settings_duration_when_pressing_skip_forward
import listenup.composeapp.generated.resources.settings_haptic_feedback
import listenup.composeapp.generated.resources.settings_haptic_feedback_subtitle
import listenup.composeapp.generated.resources.settings_hide_series_with_only_one
import listenup.composeapp.generated.resources.settings_hide_singlebook_series
import listenup.composeapp.generated.resources.settings_ignore_articles_when_sorting
import listenup.composeapp.generated.resources.notifications_settings_row_subtitle
import listenup.composeapp.generated.resources.notifications_settings_row_title
import listenup.composeapp.generated.resources.settings_manage_storage
import listenup.composeapp.generated.resources.settings_open_source_licenses
import listenup.composeapp.generated.resources.settings_rewind_a_few_seconds_when
import listenup.composeapp.generated.resources.settings_server_version
import listenup.composeapp.generated.resources.settings_share_logs
import listenup.composeapp.generated.resources.settings_test_notification
import listenup.composeapp.generated.resources.settings_test_notification_sent
import listenup.composeapp.generated.resources.settings_test_notification_subtitle
import listenup.composeapp.generated.resources.settings_share_logs_subtitle
import listenup.composeapp.generated.resources.settings_skip_backward
import listenup.composeapp.generated.resources.settings_skip_forward
import listenup.composeapp.generated.resources.settings_sleep_timer
import listenup.composeapp.generated.resources.settings_sort_ignoring_leading_articles_a
import listenup.composeapp.generated.resources.settings_speed_used_for_new_books
import listenup.composeapp.generated.resources.settings_view_and_manage_downloaded_audiobooks
import listenup.composeapp.generated.resources.settings_view_thirdparty_licenses
import listenup.composeapp.generated.resources.settings_wifi_only_downloads
import listenup.composeapp.generated.resources.settings_wifi_only_downloads_subtitle
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import com.calypsan.listenup.client.features.nowplaying.formatPlaybackSpeed
import com.calypsan.listenup.client.presentation.nowplaying.PLAYBACK_SPEED_STEPS
import org.koin.compose.viewmodel.koinViewModel

/**
 * Preset durations for skip forward button (in seconds).
 */
@Suppress("MagicNumber")
object SkipForwardPresets {
    val presets = listOf(10, 15, 20, 30, 45, 60, 90, 120)

    fun format(seconds: Int): String =
        when {
            seconds >= 60 && seconds % 60 == 0 -> "${seconds / 60} min"
            seconds >= 60 -> "${seconds / 60}m ${seconds % 60}s"
            else -> "${seconds}s"
        }
}

/**
 * Preset durations for skip backward button (in seconds).
 */
@Suppress("MagicNumber")
object SkipBackwardPresets {
    val presets = listOf(5, 10, 15, 20, 30, 45, 60)

    fun format(seconds: Int): String =
        when {
            seconds >= 60 && seconds % 60 == 0 -> "${seconds / 60} min"
            seconds >= 60 -> "${seconds / 60}m ${seconds % 60}s"
            else -> "${seconds}s"
        }
}

/**
 * Preset durations for sleep timer (in minutes).
 * Includes "Off" option represented as null.
 */
@Suppress("MagicNumber")
object SleepTimerPresets {
    val presets: List<Int?> = listOf(null, 5, 10, 15, 20, 30, 45, 60, 90, 120)

    fun format(minutes: Int?): String =
        when (minutes) {
            null -> "Off"
            60 -> "1 hour"
            90 -> "1.5 hours"
            120 -> "2 hours"
            else -> "$minutes min"
        }
}

/**
 * Settings screen.
 *
 * Displays user-configurable settings organized by category, each as an accent-themed group:
 * - Appearance: Theme, dynamic colors
 * - Playback: Speed, skip intervals, auto-rewind
 * - Sleep Timer: Default duration
 * - Library: Sorting and display options
 * - Account: Server info, devices, sign out
 * - About: Version information, licenses
 *
 * @param onNavigateBack Callback to navigate back
 * @param onNavigateToDevices Optional callback to navigate to the devices screen
 * @param onNavigateToStorage Optional callback to navigate to the storage screen
 * @param onNavigateToLicenses Optional callback to navigate to licenses screen
 * @param onNavigateToNotificationSettings Optional callback to navigate to notification settings
 * @param showDynamicColors Whether the dynamic-colors toggle is available on this platform
 * @param showSleepTimer Whether the sleep-timer group is shown
 * @param viewModel SettingsViewModel injected via Koin
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToDevices: (() -> Unit)? = null,
    onNavigateToStorage: (() -> Unit)? = null,
    onNavigateToLicenses: (() -> Unit)? = null,
    onNavigateToNotificationSettings: (() -> Unit)? = null,
    showDynamicColors: Boolean = false,
    showSleepTimer: Boolean = true,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val platformActions: SettingsPlatformActions = koinInject()
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showSignOutDialog by remember { mutableStateOf(false) }

    if (showSignOutDialog) {
        SignOutConfirmDialog(
            onConfirm = {
                viewModel.signOut()
                showSignOutDialog = false
            },
            onDismiss = { showSignOutDialog = false },
        )
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val testNotificationSentMessage = stringResource(Res.string.settings_test_notification_sent)
    LaunchedEffect(Unit) {
        // One-shot events, consumed exactly once — a StateFlow here would replay the confirmation
        // on every recomposition. Failures already reach the global snackbar via the ErrorBus, so
        // only the success needs saying locally.
        viewModel.events.collect { event ->
            if (event is SettingsEvent.TestNotificationSent) {
                snackbarHostState.showSnackbar(testNotificationSentMessage)
            }
        }
    }

    val actions =
        remember(viewModel) {
            SettingsActions(
                onThemeModeChange = viewModel::setThemeMode,
                onDynamicColorsChange = viewModel::setDynamicColorsEnabled,
                onPlaybackSpeedChange = viewModel::setDefaultPlaybackSpeed,
                onVolumeBoostChange = viewModel::setDefaultVolumeBoostDb,
                onSkipForwardChange = viewModel::setDefaultSkipForwardSec,
                onSkipBackwardChange = viewModel::setDefaultSkipBackwardSec,
                onAutoRewindChange = viewModel::setAutoRewindEnabled,
                onSleepTimerChange = viewModel::setDefaultSleepTimerMin,
                onIgnoreTitleArticlesChange = viewModel::setIgnoreTitleArticles,
                onHideSingleBookSeriesChange = viewModel::setHideSingleBookSeries,
                onHapticFeedbackChange = viewModel::setHapticFeedbackEnabled,
                onWifiOnlyDownloadsChange = viewModel::setWifiOnlyDownloads,
            )
        }

    ListenUpScaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            ListenUpTopAppBar(
                title = stringResource(Res.string.common_settings),
                onBack = onNavigateBack,
            )
        },
    ) { padding ->
        SettingsContent(
            state = state,
            actions = actions,
            showDynamicColors = showDynamicColors,
            showSleepTimer = showSleepTimer,
            onNavigateToDevices = onNavigateToDevices,
            onNavigateToStorage = onNavigateToStorage,
            onNavigateToLicenses = onNavigateToLicenses,
            onNavigateToNotificationSettings = onNavigateToNotificationSettings,
            onSignOutClick = { showSignOutDialog = true },
            onShareLogs = platformActions::shareLogs,
            onSendTestNotification = viewModel::sendTestNotification,
            modifier = Modifier.padding(padding),
        )
    }
}

/**
 * The setting changes the Settings sections make, bound once to [SettingsViewModel] by
 * [SettingsScreen] — so [SettingsContent] renders from state alone and can be hosted without Koin.
 */
internal class SettingsActions(
    val onThemeModeChange: (ThemeMode) -> Unit,
    val onDynamicColorsChange: (Boolean) -> Unit,
    val onPlaybackSpeedChange: (Float) -> Unit,
    val onVolumeBoostChange: (Float) -> Unit,
    val onSkipForwardChange: (Int) -> Unit,
    val onSkipBackwardChange: (Int) -> Unit,
    val onAutoRewindChange: (Boolean) -> Unit,
    val onSleepTimerChange: (Int?) -> Unit,
    val onIgnoreTitleArticlesChange: (Boolean) -> Unit,
    val onHideSingleBookSeriesChange: (Boolean) -> Unit,
    val onHapticFeedbackChange: (Boolean) -> Unit,
    val onWifiOnlyDownloadsChange: (Boolean) -> Unit,
)

/**
 * The Settings sections under the top bar. A phone gets them as one column; from the medium width
 * up they spread into [SectionColumns] — as many columns as the window affords — so a tablet reads
 * Settings as a page of grouped cards rather than a phone column adrift in the middle.
 */
@Composable
internal fun SettingsContent(
    state: SettingsUiState,
    actions: SettingsActions,
    showDynamicColors: Boolean,
    showSleepTimer: Boolean,
    onNavigateToDevices: (() -> Unit)?,
    onNavigateToStorage: (() -> Unit)?,
    onNavigateToLicenses: (() -> Unit)?,
    onNavigateToNotificationSettings: (() -> Unit)?,
    onSignOutClick: () -> Unit,
    onShareLogs: () -> Unit,
    onSendTestNotification: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isWide =
        currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(
            WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND,
        )
    val appearance: @Composable () -> Unit = {
        AppearanceSection(state = state, showDynamicColors = showDynamicColors, actions = actions)
    }
    val playback: @Composable () -> Unit = { PlaybackSection(state = state, actions = actions) }
    val sleepTimer: @Composable () -> Unit = { SleepTimerSection(state = state, actions = actions) }
    val library: @Composable () -> Unit = { LibrarySection(state = state, actions = actions) }
    val account: @Composable () -> Unit = {
        AccountSection(
            state = state,
            onNavigateToDevices = onNavigateToDevices,
            onSignOutClick = onSignOutClick,
            actions = actions,
        )
    }
    val downloads: @Composable () -> Unit = { DownloadsSection(state = state, actions = actions) }
    val about: @Composable () -> Unit = {
        AboutSection(
            state = state,
            onNavigateToLicenses = onNavigateToLicenses,
            onShareLogs = onShareLogs,
            onSendTestNotification = onSendTestNotification,
            onNavigateToNotificationSettings = onNavigateToNotificationSettings,
        )
    }

    if (isWide) {
        SectionColumns(
            modifier =
                modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.screenMargin, vertical = 16.dp),
        ) {
            section(appearance)
            section(playback)
            if (showSleepTimer) section(sleepTimer)
            section(library)
            // The sign-out tile belongs to its account group; one section keeps them together.
            section { Column(verticalArrangement = Arrangement.spacedBy(Spacing.sectionGap)) { account() } }
            section(downloads)
            onNavigateToStorage?.let { section { StorageSection(onNavigateToStorage = it) } }
            section(about)
        }
    } else {
        Column(
            modifier =
                modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sectionGap),
        ) {
            appearance()
            playback()
            if (showSleepTimer) sleepTimer()
            library()
            account()
            downloads()
            onNavigateToStorage?.let { StorageSection(onNavigateToStorage = it) }
            about()
        }
    }
}

@Composable
private fun AppearanceSection(
    state: SettingsUiState,
    showDynamicColors: Boolean,
    actions: SettingsActions,
) {
    SectionGroup(
        label = stringResource(Res.string.settings_appearance),
    ) {
        SelectorRow(
            icon = Icons.Default.DarkMode,
            accent = MaterialTheme.colorScheme.primary,
            title = stringResource(Res.string.common_theme),
            subtitle = stringResource(Res.string.settings_choose_light_dark_or_follow),
            selectedValue = state.themeMode,
            options = ThemeMode.entries.toList(),
            formatValue = { mode ->
                when (mode) {
                    ThemeMode.SYSTEM -> "System"
                    ThemeMode.LIGHT -> "Light"
                    ThemeMode.DARK -> "Dark"
                }
            },
            onValueSelected = actions.onThemeModeChange,
        )
        if (showDynamicColors) {
            SettingToggleRow(
                icon = Icons.Default.Palette,
                accent = MaterialTheme.colorScheme.primary,
                title = "Dynamic colors",
                subtitle = "Use colors from your wallpaper (Material You)",
                checked = state.dynamicColorsEnabled,
                onCheckedChange = actions.onDynamicColorsChange,
            )
        }
    }
}

@Composable
private fun PlaybackSection(
    state: SettingsUiState,
    actions: SettingsActions,
) {
    val accent = MaterialTheme.colorScheme.tertiary
    val pillContainer = MaterialTheme.colorScheme.tertiaryContainer
    val pillContent = MaterialTheme.colorScheme.onTertiaryContainer
    SectionGroup(
        label = stringResource(Res.string.common_playback),
    ) {
        SelectorRow(
            icon = Icons.Default.Speed,
            accent = accent,
            title = stringResource(Res.string.settings_default_speed),
            subtitle = stringResource(Res.string.settings_speed_used_for_new_books),
            selectedValue = state.defaultPlaybackSpeed,
            options = PLAYBACK_SPEED_STEPS,
            formatValue = { formatPlaybackSpeed(it) },
            onValueSelected = actions.onPlaybackSpeedChange,
            pillContainerColor = pillContainer,
            pillContentColor = pillContent,
        )
        val boostOffLabel = stringResource(Res.string.player_boost_off)
        val boostLabels =
            VolumeBoostPresets.presets.associateWith { db ->
                VolumeBoostPresets.format(
                    db = db,
                    offLabel = boostOffLabel,
                    dbLabel = stringResource(Res.string.player_boost_db, db.roundToInt()),
                )
            }
        // The saved default is clamped to the boost range but never snapped to a preset, so a
        // value synced from another client can fall between the steps the map covers. Formatting
        // it up front keeps the lookup below total.
        val currentBoostLabel =
            VolumeBoostPresets.format(
                db = state.defaultVolumeBoostDb,
                offLabel = boostOffLabel,
                dbLabel = stringResource(Res.string.player_boost_db, state.defaultVolumeBoostDb.roundToInt()),
            )
        SelectorRow(
            icon = Icons.AutoMirrored.Filled.VolumeUp,
            accent = accent,
            title = stringResource(Res.string.settings_default_boost),
            subtitle = stringResource(Res.string.settings_boost_used_for_new_books),
            selectedValue = state.defaultVolumeBoostDb,
            options = VolumeBoostPresets.presets,
            formatValue = { boostLabels[it] ?: currentBoostLabel },
            onValueSelected = actions.onVolumeBoostChange,
            pillContainerColor = pillContainer,
            pillContentColor = pillContent,
        )
        SelectorRow(
            icon = Icons.Default.Forward30,
            accent = accent,
            title = stringResource(Res.string.settings_skip_forward),
            subtitle = stringResource(Res.string.settings_duration_when_pressing_skip_forward),
            selectedValue = state.defaultSkipForwardSec,
            options = SkipForwardPresets.presets,
            formatValue = { SkipForwardPresets.format(it) },
            onValueSelected = actions.onSkipForwardChange,
            pillContainerColor = pillContainer,
            pillContentColor = pillContent,
        )
        SelectorRow(
            icon = Icons.Default.Replay10,
            accent = accent,
            title = stringResource(Res.string.settings_skip_backward),
            subtitle = stringResource(Res.string.settings_duration_when_pressing_skip_backward),
            selectedValue = state.defaultSkipBackwardSec,
            options = SkipBackwardPresets.presets,
            formatValue = { SkipBackwardPresets.format(it) },
            onValueSelected = actions.onSkipBackwardChange,
            pillContainerColor = pillContainer,
            pillContentColor = pillContent,
        )
        SettingToggleRow(
            icon = Icons.Default.History,
            accent = accent,
            title = stringResource(Res.string.settings_autorewind_on_resume),
            subtitle = stringResource(Res.string.settings_rewind_a_few_seconds_when),
            checked = state.autoRewindEnabled,
            onCheckedChange = actions.onAutoRewindChange,
        )
    }
}

@Composable
private fun SleepTimerSection(
    state: SettingsUiState,
    actions: SettingsActions,
) {
    val accent = MaterialTheme.colorScheme.secondary
    SectionGroup(
        label = stringResource(Res.string.settings_sleep_timer),
    ) {
        SelectorRow(
            icon = Icons.Default.Timer,
            accent = accent,
            title = stringResource(Res.string.settings_default_timer),
            subtitle = stringResource(Res.string.settings_autostart_sleep_timer_when_playing),
            selectedValue = state.defaultSleepTimerMin,
            options = SleepTimerPresets.presets,
            formatValue = { SleepTimerPresets.format(it) },
            onValueSelected = actions.onSleepTimerChange,
            pillContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            pillContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun LibrarySection(
    state: SettingsUiState,
    actions: SettingsActions,
) {
    val accent = MaterialTheme.colorScheme.primary
    SectionGroup(
        label = stringResource(Res.string.common_library),
    ) {
        SettingToggleRow(
            icon = Icons.Default.SortByAlpha,
            accent = accent,
            title = stringResource(Res.string.settings_ignore_articles_when_sorting),
            subtitle = stringResource(Res.string.settings_sort_ignoring_leading_articles_a),
            checked = state.ignoreTitleArticles,
            onCheckedChange = actions.onIgnoreTitleArticlesChange,
        )
        SettingToggleRow(
            icon = Icons.Default.FilterNone,
            accent = accent,
            title = stringResource(Res.string.settings_hide_singlebook_series),
            subtitle = stringResource(Res.string.settings_hide_series_with_only_one),
            checked = state.hideSingleBookSeries,
            onCheckedChange = actions.onHideSingleBookSeriesChange,
        )
    }
}

@Composable
private fun AccountSection(
    state: SettingsUiState,
    onNavigateToDevices: (() -> Unit)?,
    onSignOutClick: () -> Unit,
    actions: SettingsActions,
) {
    val accent = MaterialTheme.colorScheme.primary
    SectionGroup(
        label = stringResource(Res.string.common_account),
    ) {
        state.serverUrl?.let { url ->
            InfoRow(
                icon = Icons.Default.Dns,
                accent = accent,
                title = stringResource(Res.string.common_server),
                value = url.removePrefix("https://").removePrefix("http://"),
            )
        }
        if (onNavigateToDevices != null) {
            SettingNavigationRow(
                icon = Icons.Default.Devices,
                accent = accent,
                title = stringResource(Res.string.settings_devices),
                subtitle = stringResource(Res.string.devices_manage_active_sessions),
                onClick = onNavigateToDevices,
            )
        }
        SettingToggleRow(
            icon = Icons.Default.Vibration,
            accent = accent,
            title = stringResource(Res.string.settings_haptic_feedback),
            subtitle = stringResource(Res.string.settings_haptic_feedback_subtitle),
            checked = state.hapticFeedbackEnabled,
            onCheckedChange = actions.onHapticFeedbackChange,
        )
    }
    SignOutTile(onClick = onSignOutClick)
}

@Composable
private fun DownloadsSection(
    state: SettingsUiState,
    actions: SettingsActions,
) {
    val accent = MaterialTheme.colorScheme.tertiary
    SectionGroup(
        label = stringResource(Res.string.settings_downloads),
    ) {
        SettingToggleRow(
            icon = Icons.Default.Wifi,
            accent = accent,
            title = stringResource(Res.string.settings_wifi_only_downloads),
            subtitle = stringResource(Res.string.settings_wifi_only_downloads_subtitle),
            checked = state.wifiOnlyDownloads,
            onCheckedChange = actions.onWifiOnlyDownloadsChange,
        )
    }
}

@Composable
private fun StorageSection(onNavigateToStorage: () -> Unit) {
    val accent = MaterialTheme.colorScheme.tertiary
    SectionGroup(
        label = stringResource(Res.string.common_storage),
    ) {
        SettingNavigationRow(
            icon = Icons.Default.Download,
            accent = accent,
            title = stringResource(Res.string.settings_manage_storage),
            subtitle = stringResource(Res.string.settings_view_and_manage_downloaded_audiobooks),
            onClick = onNavigateToStorage,
        )
    }
}

@Composable
private fun AboutSection(
    state: SettingsUiState,
    onNavigateToLicenses: (() -> Unit)?,
    onShareLogs: () -> Unit,
    onSendTestNotification: () -> Unit,
    onNavigateToNotificationSettings: (() -> Unit)?,
) {
    val accent = MaterialTheme.colorScheme.onSurfaceVariant
    SectionGroup(
        label = stringResource(Res.string.common_about),
    ) {
        InfoRow(
            icon = Icons.Default.Verified,
            accent = accent,
            title = stringResource(Res.string.settings_app_version),
            value = state.appVersion,
        )
        state.serverVersion?.let { version ->
            InfoRow(
                icon = Icons.Default.Dns,
                accent = accent,
                title = stringResource(Res.string.settings_server_version),
                value = version,
            )
        }
        if (onNavigateToLicenses != null) {
            SettingNavigationRow(
                icon = Icons.Default.Gavel,
                accent = accent,
                title = stringResource(Res.string.settings_open_source_licenses),
                subtitle = stringResource(Res.string.settings_view_thirdparty_licenses),
                onClick = onNavigateToLicenses,
            )
        }
        SettingRow(
            icon = Icons.Default.Share,
            accent = accent,
            title = stringResource(Res.string.settings_share_logs),
            subtitle = stringResource(Res.string.settings_share_logs_subtitle),
            onClick = onShareLogs,
        )
        if (onNavigateToNotificationSettings != null) {
            SettingNavigationRow(
                icon = Icons.Default.Notifications,
                accent = accent,
                title = stringResource(Res.string.notifications_settings_row_title),
                subtitle = stringResource(Res.string.notifications_settings_row_subtitle),
                onClick = onNavigateToNotificationSettings,
            )
        }
        // Beside Share logs deliberately: both answer "is this thing actually working?", which is
        // the only question a user has when a notification never arrived.
        SettingRow(
            icon = Icons.Default.NotificationsActive,
            accent = accent,
            title = stringResource(Res.string.settings_test_notification),
            subtitle = stringResource(Res.string.settings_test_notification_subtitle),
            onClick = onSendTestNotification,
        )
    }
}

@Composable
private fun <T> SelectorRow(
    icon: ImageVector,
    accent: Color,
    title: String,
    subtitle: String,
    selectedValue: T,
    options: List<T>,
    formatValue: (T) -> String,
    onValueSelected: (T) -> Unit,
    pillContainerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    pillContentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    val haptics = LocalHaptics.current
    var expanded by remember { mutableStateOf(false) }
    SettingRow(
        icon = icon,
        accent = accent,
        title = title,
        subtitle = subtitle,
    ) {
        Box {
            ValuePill(
                value = formatValue(selectedValue),
                onClick = { expanded = true },
                containerColor = pillContainerColor,
                contentColor = pillContentColor,
            )
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                options.forEach { option ->
                    val isCurrent = option == selectedValue
                    DropdownMenuItem(
                        text = { Text(formatValue(option)) },
                        onClick = {
                            haptics.selectionTick()
                            onValueSelected(option)
                            expanded = false
                        },
                        // The current value is marked, and said: one choice of several.
                        modifier =
                            Modifier.semantics {
                                role = Role.RadioButton
                                selected = isCurrent
                            },
                        trailingIcon =
                            if (isCurrent) {
                                { Icon(Icons.Default.Check, contentDescription = null) }
                            } else {
                                null
                            },
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoRow(
    icon: ImageVector,
    accent: Color,
    title: String,
    value: String,
) {
    SettingRow(
        icon = icon,
        accent = accent,
        title = title,
        subtitle = null,
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Full-width destructive tile that opens the sign-out confirmation dialog. */
@Composable
private fun SignOutTile(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().semantics { role = Role.Button },
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(11.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Logout,
                contentDescription = null,
            )
            Text(
                text = stringResource(Res.string.common_sign_out),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
