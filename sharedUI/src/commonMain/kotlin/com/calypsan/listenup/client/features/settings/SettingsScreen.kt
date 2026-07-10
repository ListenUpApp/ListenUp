package com.calypsan.listenup.client.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FilterNone
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.calypsan.listenup.client.design.components.ListenUpScaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import com.calypsan.listenup.client.design.components.SectionGroup
import com.calypsan.listenup.client.design.components.SettingRow
import com.calypsan.listenup.client.design.components.ValuePill
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.domain.model.ThemeMode
import com.calypsan.listenup.client.presentation.settings.SettingsUiState
import com.calypsan.listenup.client.presentation.settings.SettingsViewModel
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_about
import listenup.composeapp.generated.resources.common_account
import listenup.composeapp.generated.resources.common_back
import listenup.composeapp.generated.resources.common_cancel
import listenup.composeapp.generated.resources.common_library
import listenup.composeapp.generated.resources.common_playback
import listenup.composeapp.generated.resources.common_server
import listenup.composeapp.generated.resources.common_settings
import listenup.composeapp.generated.resources.common_sign_out
import listenup.composeapp.generated.resources.common_storage
import listenup.composeapp.generated.resources.common_theme
import listenup.composeapp.generated.resources.devices_manage_active_sessions
import listenup.composeapp.generated.resources.settings_app_version
import listenup.composeapp.generated.resources.settings_appearance
import listenup.composeapp.generated.resources.settings_are_you_sure_you_want
import listenup.composeapp.generated.resources.settings_autorewind_on_resume
import listenup.composeapp.generated.resources.settings_autostart_sleep_timer_when_playing
import listenup.composeapp.generated.resources.settings_choose_light_dark_or_follow
import listenup.composeapp.generated.resources.settings_default_speed
import listenup.composeapp.generated.resources.settings_default_timer
import listenup.composeapp.generated.resources.settings_desktop
import listenup.composeapp.generated.resources.settings_devices
import listenup.composeapp.generated.resources.settings_duration_when_pressing_skip_backward
import listenup.composeapp.generated.resources.settings_duration_when_pressing_skip_forward
import listenup.composeapp.generated.resources.settings_hide_series_with_only_one
import listenup.composeapp.generated.resources.settings_hide_singlebook_series
import listenup.composeapp.generated.resources.settings_ignore_articles_when_sorting
import listenup.composeapp.generated.resources.settings_manage_storage
import listenup.composeapp.generated.resources.settings_open_source_licenses
import listenup.composeapp.generated.resources.settings_rewind_a_few_seconds_when
import listenup.composeapp.generated.resources.settings_server_version
import listenup.composeapp.generated.resources.settings_skip_backward
import listenup.composeapp.generated.resources.settings_skip_forward
import listenup.composeapp.generated.resources.settings_sleep_timer
import listenup.composeapp.generated.resources.settings_sort_ignoring_leading_articles_a
import listenup.composeapp.generated.resources.settings_speed_used_for_new_books
import listenup.composeapp.generated.resources.settings_view_and_manage_downloaded_audiobooks
import listenup.composeapp.generated.resources.settings_view_thirdparty_licenses
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * Preset playback speeds.
 */
object PlaybackSpeedPresets {
    val presets = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)

    fun format(speed: Float): String =
        if (speed == speed.toInt().toFloat()) {
            "${speed.toInt()}.0x"
        } else {
            val formatted = "%.2f".format(speed).trimEnd('0').trimEnd('.')
            "${formatted}x"
        }
}

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

/** Max readable content width — wide windows centre the settings column rather than stretch it. */
private val ContentMaxWidth = 640.dp

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
    showDynamicColors: Boolean = false,
    showSleepTimer: Boolean = true,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showSignOutDialog by remember { mutableStateOf(false) }

    // Sign out confirmation dialog
    if (showSignOutDialog) {
        AlertDialog(
            onDismissRequest = { showSignOutDialog = false },
            shape = MaterialTheme.shapes.large,
            title = { Text(stringResource(Res.string.common_sign_out)) },
            text = { Text(stringResource(Res.string.settings_are_you_sure_you_want)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.signOut()
                        showSignOutDialog = false
                    },
                ) {
                    Text(stringResource(Res.string.common_sign_out))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutDialog = false }) {
                    Text(stringResource(Res.string.common_cancel))
                }
            },
        )
    }

    ListenUpScaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.common_settings)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.common_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier =
                    Modifier
                        .widthIn(max = ContentMaxWidth)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                AppearanceSection(
                    state = state,
                    showDynamicColors = showDynamicColors,
                    viewModel = viewModel,
                )

                PlaybackSection(state = state, viewModel = viewModel)

                if (showSleepTimer) {
                    SleepTimerSection(state = state, viewModel = viewModel)
                }

                LibrarySection(state = state, viewModel = viewModel)

                AccountSection(
                    state = state,
                    onNavigateToDevices = onNavigateToDevices,
                    onSignOutClick = { showSignOutDialog = true },
                )

                if (onNavigateToStorage != null) {
                    StorageSection(onNavigateToStorage = onNavigateToStorage)
                }

                AboutSection(state = state, onNavigateToLicenses = onNavigateToLicenses)
            }
        }
    }
}

@Composable
private fun AppearanceSection(
    state: SettingsUiState,
    showDynamicColors: Boolean,
    viewModel: SettingsViewModel,
) {
    SectionGroup(
        icon = Icons.Default.Palette,
        label = stringResource(Res.string.settings_appearance),
        accent = MaterialTheme.colorScheme.primary,
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
            onValueSelected = viewModel::setThemeMode,
        )
        if (showDynamicColors) {
            ToggleRow(
                icon = Icons.Default.Palette,
                accent = MaterialTheme.colorScheme.primary,
                title = "Dynamic colors",
                subtitle = "Use colors from your wallpaper (Material You)",
                checked = state.dynamicColorsEnabled,
                onCheckedChange = viewModel::setDynamicColorsEnabled,
                showDivider = true,
            )
        }
    }
}

@Composable
private fun PlaybackSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
) {
    val accent = MaterialTheme.colorScheme.tertiary
    val pillContainer = MaterialTheme.colorScheme.tertiaryContainer
    val pillContent = MaterialTheme.colorScheme.onTertiaryContainer
    SectionGroup(
        icon = Icons.Default.PlayCircle,
        label = stringResource(Res.string.common_playback),
        accent = accent,
    ) {
        SelectorRow(
            icon = Icons.Default.Speed,
            accent = accent,
            title = stringResource(Res.string.settings_default_speed),
            subtitle = stringResource(Res.string.settings_speed_used_for_new_books),
            selectedValue = state.defaultPlaybackSpeed,
            options = PlaybackSpeedPresets.presets,
            formatValue = { PlaybackSpeedPresets.format(it) },
            onValueSelected = viewModel::setDefaultPlaybackSpeed,
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
            onValueSelected = viewModel::setDefaultSkipForwardSec,
            pillContainerColor = pillContainer,
            pillContentColor = pillContent,
            showDivider = true,
        )
        SelectorRow(
            icon = Icons.Default.Replay10,
            accent = accent,
            title = stringResource(Res.string.settings_skip_backward),
            subtitle = stringResource(Res.string.settings_duration_when_pressing_skip_backward),
            selectedValue = state.defaultSkipBackwardSec,
            options = SkipBackwardPresets.presets,
            formatValue = { SkipBackwardPresets.format(it) },
            onValueSelected = viewModel::setDefaultSkipBackwardSec,
            pillContainerColor = pillContainer,
            pillContentColor = pillContent,
            showDivider = true,
        )
        ToggleRow(
            icon = Icons.Default.History,
            accent = accent,
            title = stringResource(Res.string.settings_autorewind_on_resume),
            subtitle = stringResource(Res.string.settings_rewind_a_few_seconds_when),
            checked = state.autoRewindEnabled,
            onCheckedChange = viewModel::setAutoRewindEnabled,
            showDivider = false,
        )
    }
}

@Composable
private fun SleepTimerSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
) {
    val accent = MaterialTheme.colorScheme.secondary
    SectionGroup(
        icon = Icons.Default.Bedtime,
        label = stringResource(Res.string.settings_sleep_timer),
        accent = accent,
    ) {
        SelectorRow(
            icon = Icons.Default.Timer,
            accent = accent,
            title = stringResource(Res.string.settings_default_timer),
            subtitle = stringResource(Res.string.settings_autostart_sleep_timer_when_playing),
            selectedValue = state.defaultSleepTimerMin,
            options = SleepTimerPresets.presets,
            formatValue = { SleepTimerPresets.format(it) },
            onValueSelected = viewModel::setDefaultSleepTimerMin,
            pillContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            pillContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun LibrarySection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
) {
    val accent = MaterialTheme.colorScheme.primary
    SectionGroup(
        icon = Icons.AutoMirrored.Filled.LibraryBooks,
        label = stringResource(Res.string.common_library),
        accent = accent,
    ) {
        ToggleRow(
            icon = Icons.Default.SortByAlpha,
            accent = accent,
            title = stringResource(Res.string.settings_ignore_articles_when_sorting),
            subtitle = stringResource(Res.string.settings_sort_ignoring_leading_articles_a),
            checked = state.ignoreTitleArticles,
            onCheckedChange = viewModel::setIgnoreTitleArticles,
        )
        ToggleRow(
            icon = Icons.Default.FilterNone,
            accent = accent,
            title = stringResource(Res.string.settings_hide_singlebook_series),
            subtitle = stringResource(Res.string.settings_hide_series_with_only_one),
            checked = state.hideSingleBookSeries,
            onCheckedChange = viewModel::setHideSingleBookSeries,
            showDivider = true,
        )
    }
}

@Composable
private fun AccountSection(
    state: SettingsUiState,
    onNavigateToDevices: (() -> Unit)?,
    onSignOutClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    SectionGroup(
        icon = Icons.Default.PersonOutline,
        label = stringResource(Res.string.common_account),
        accent = accent,
    ) {
        val hasServerRow = state.serverUrl != null
        state.serverUrl?.let { url ->
            InfoRow(
                icon = Icons.Default.Dns,
                accent = accent,
                title = stringResource(Res.string.common_server),
                value = url.removePrefix("https://").removePrefix("http://"),
            )
        }
        if (onNavigateToDevices != null) {
            NavigationRow(
                icon = Icons.Default.Devices,
                accent = accent,
                title = stringResource(Res.string.settings_devices),
                subtitle = stringResource(Res.string.devices_manage_active_sessions),
                onClick = onNavigateToDevices,
                showDivider = hasServerRow,
            )
        }
    }
    SignOutTile(onClick = onSignOutClick)
}

@Composable
private fun StorageSection(onNavigateToStorage: () -> Unit) {
    val accent = MaterialTheme.colorScheme.tertiary
    SectionGroup(
        icon = Icons.Default.Storage,
        label = stringResource(Res.string.common_storage),
        accent = accent,
    ) {
        NavigationRow(
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
) {
    val accent = MaterialTheme.colorScheme.onSurfaceVariant
    SectionGroup(
        icon = Icons.Default.Info,
        label = stringResource(Res.string.common_about),
        accent = accent,
    ) {
        InfoRow(
            icon = Icons.Default.Verified,
            accent = accent,
            title = stringResource(Res.string.settings_app_version),
            value = stringResource(Res.string.settings_desktop),
        )
        state.serverVersion?.let { version ->
            InfoRow(
                icon = Icons.Default.Dns,
                accent = accent,
                title = stringResource(Res.string.settings_server_version),
                value = version,
                showDivider = true,
            )
        }
        if (onNavigateToLicenses != null) {
            NavigationRow(
                icon = Icons.Default.Gavel,
                accent = accent,
                title = stringResource(Res.string.settings_open_source_licenses),
                subtitle = stringResource(Res.string.settings_view_thirdparty_licenses),
                onClick = onNavigateToLicenses,
                showDivider = true,
            )
        }
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
    showDivider: Boolean = false,
) {
    val haptics = LocalHaptics.current
    var expanded by remember { mutableStateOf(false) }
    SettingRow(
        icon = icon,
        accent = accent,
        title = title,
        subtitle = subtitle,
        showDivider = showDivider,
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
                    DropdownMenuItem(
                        text = { Text(formatValue(option)) },
                        onClick = {
                            haptics.selectionTick()
                            onValueSelected(option)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(
    icon: ImageVector,
    accent: Color,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    showDivider: Boolean = false,
) {
    val haptics = LocalHaptics.current
    SettingRow(
        icon = icon,
        accent = accent,
        title = title,
        subtitle = subtitle,
        showDivider = showDivider,
    ) {
        Switch(
            checked = checked,
            onCheckedChange = { newValue ->
                haptics.toggle(on = newValue)
                onCheckedChange(newValue)
            },
        )
    }
}

@Composable
private fun NavigationRow(
    icon: ImageVector,
    accent: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    showDivider: Boolean = false,
) {
    SettingRow(
        icon = icon,
        accent = accent,
        title = title,
        subtitle = subtitle,
        showDivider = showDivider,
        onClick = onClick,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InfoRow(
    icon: ImageVector,
    accent: Color,
    title: String,
    value: String,
    showDivider: Boolean = false,
) {
    SettingRow(
        icon = icon,
        accent = accent,
        title = title,
        subtitle = null,
        showDivider = showDivider,
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
        modifier = Modifier.fillMaxWidth(),
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
