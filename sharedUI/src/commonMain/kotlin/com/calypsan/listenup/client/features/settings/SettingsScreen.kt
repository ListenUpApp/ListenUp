@file:Suppress("LongMethod")

package com.calypsan.listenup.client.features.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.domain.model.ThemeMode
import com.calypsan.listenup.client.presentation.settings.SettingsViewModel
import org.koin.compose.viewmodel.koinViewModel
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_back
import listenup.composeapp.generated.resources.common_about
import listenup.composeapp.generated.resources.common_library
import listenup.composeapp.generated.resources.common_cancel
import listenup.composeapp.generated.resources.common_settings
import listenup.composeapp.generated.resources.common_sign_out
import listenup.composeapp.generated.resources.settings_51_surround_sound_for_immersive
import listenup.composeapp.generated.resources.common_account
import listenup.composeapp.generated.resources.settings_app_version
import listenup.composeapp.generated.resources.settings_appearance
import listenup.composeapp.generated.resources.settings_are_you_sure_you_want
import listenup.composeapp.generated.resources.settings_autorewind_on_resume
import listenup.composeapp.generated.resources.settings_autostart_sleep_timer_when_playing
import listenup.composeapp.generated.resources.settings_choose_light_dark_or_follow
import listenup.composeapp.generated.resources.settings_default_speed
import listenup.composeapp.generated.resources.settings_default_timer
import listenup.composeapp.generated.resources.settings_desktop
import listenup.composeapp.generated.resources.settings_duration_when_pressing_skip_backward
import listenup.composeapp.generated.resources.settings_duration_when_pressing_skip_forward
import listenup.composeapp.generated.resources.settings_hide_series_with_only_one
import listenup.composeapp.generated.resources.settings_hide_singlebook_series
import listenup.composeapp.generated.resources.settings_ignore_articles_when_sorting
import listenup.composeapp.generated.resources.settings_manage_storage
import listenup.composeapp.generated.resources.settings_open_source_licenses
import listenup.composeapp.generated.resources.common_playback
import listenup.composeapp.generated.resources.settings_rewind_a_few_seconds_when
import listenup.composeapp.generated.resources.common_server
import listenup.composeapp.generated.resources.settings_server_version
import listenup.composeapp.generated.resources.settings_skip_backward
import listenup.composeapp.generated.resources.settings_skip_forward
import listenup.composeapp.generated.resources.settings_sleep_timer
import listenup.composeapp.generated.resources.settings_sort_ignoring_leading_articles_a
import listenup.composeapp.generated.resources.settings_spatial_audio
import listenup.composeapp.generated.resources.settings_speed_used_for_new_books
import listenup.composeapp.generated.resources.common_storage
import listenup.composeapp.generated.resources.common_theme
import listenup.composeapp.generated.resources.settings_view_and_manage_downloaded_audiobooks
import listenup.composeapp.generated.resources.settings_view_thirdparty_licenses

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

/**
 * Settings screen for desktop.
 *
 * Displays user-configurable settings organized by category:
 * - Appearance: Theme
 * - Playback: Speed, skip intervals, auto-rewind, spatial audio
 * - Sleep Timer: Default duration
 * - Library: Sorting and display options
 * - Account: Server info and sign out
 * - About: Version information
 *
 * @param onNavigateBack Callback to navigate back
 * @param onNavigateToLicenses Optional callback to navigate to licenses screen
 * @param viewModel SettingsViewModel injected via Koin
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
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

    Scaffold(
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
        ) {
            // Appearance section
            SettingsSection(title = stringResource(Res.string.settings_appearance)) {
                SettingsDropdownItem(
                    title = stringResource(Res.string.common_theme),
                    description = stringResource(Res.string.settings_choose_light_dark_or_follow),
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
                    SettingsToggleItem(
                        title = "Dynamic colors",
                        description = "Use colors from your wallpaper (Material You)",
                        checked = state.dynamicColorsEnabled,
                        onCheckedChange = viewModel::setDynamicColorsEnabled,
                    )
                }
            }

            SettingsDivider()

            // Playback section
            SettingsSection(title = stringResource(Res.string.common_playback)) {
                SettingsDropdownItem(
                    title = stringResource(Res.string.settings_default_speed),
                    description = stringResource(Res.string.settings_speed_used_for_new_books),
                    selectedValue = state.defaultPlaybackSpeed,
                    options = PlaybackSpeedPresets.presets,
                    formatValue = { PlaybackSpeedPresets.format(it) },
                    onValueSelected = viewModel::setDefaultPlaybackSpeed,
                )
                SettingsDropdownItem(
                    title = stringResource(Res.string.settings_skip_forward),
                    description = stringResource(Res.string.settings_duration_when_pressing_skip_forward),
                    selectedValue = state.defaultSkipForwardSec,
                    options = SkipForwardPresets.presets,
                    formatValue = { SkipForwardPresets.format(it) },
                    onValueSelected = viewModel::setDefaultSkipForwardSec,
                )
                SettingsDropdownItem(
                    title = stringResource(Res.string.settings_skip_backward),
                    description = stringResource(Res.string.settings_duration_when_pressing_skip_backward),
                    selectedValue = state.defaultSkipBackwardSec,
                    options = SkipBackwardPresets.presets,
                    formatValue = { SkipBackwardPresets.format(it) },
                    onValueSelected = viewModel::setDefaultSkipBackwardSec,
                )
                SettingsToggleItem(
                    title = stringResource(Res.string.settings_autorewind_on_resume),
                    description = stringResource(Res.string.settings_rewind_a_few_seconds_when),
                    checked = state.autoRewindEnabled,
                    onCheckedChange = viewModel::setAutoRewindEnabled,
                )
                SettingsToggleItem(
                    title = stringResource(Res.string.settings_spatial_audio),
                    description = stringResource(Res.string.settings_51_surround_sound_for_immersive),
                    checked = state.spatialPlayback,
                    onCheckedChange = viewModel::setSpatialPlayback,
                )
            }

            if (showSleepTimer) {
                SettingsDivider()

                // Sleep timer section
                SettingsSection(title = stringResource(Res.string.settings_sleep_timer)) {
                    SettingsDropdownItem(
                        title = stringResource(Res.string.settings_default_timer),
                        description = stringResource(Res.string.settings_autostart_sleep_timer_when_playing),
                        selectedValue = state.defaultSleepTimerMin,
                        options = SleepTimerPresets.presets,
                        formatValue = { SleepTimerPresets.format(it) },
                        onValueSelected = viewModel::setDefaultSleepTimerMin,
                    )
                }
            }

            SettingsDivider()

            // Library section
            SettingsSection(title = stringResource(Res.string.common_library)) {
                SettingsToggleItem(
                    title = stringResource(Res.string.settings_ignore_articles_when_sorting),
                    description = stringResource(Res.string.settings_sort_ignoring_leading_articles_a),
                    checked = state.ignoreTitleArticles,
                    onCheckedChange = viewModel::setIgnoreTitleArticles,
                )
                SettingsToggleItem(
                    title = stringResource(Res.string.settings_hide_singlebook_series),
                    description = stringResource(Res.string.settings_hide_series_with_only_one),
                    checked = state.hideSingleBookSeries,
                    onCheckedChange = viewModel::setHideSingleBookSeries,
                )
            }

            SettingsDivider()

            // Account section
            SettingsSection(title = stringResource(Res.string.common_account)) {
                state.serverUrl?.let { url ->
                    SettingsInfoItem(
                        title = stringResource(Res.string.common_server),
                        value = url.removePrefix("https://").removePrefix("http://"),
                    )
                }
                SettingsActionItem(
                    title = stringResource(Res.string.common_sign_out),
                    icon = Icons.AutoMirrored.Filled.Logout,
                    onClick = { showSignOutDialog = true },
                    destructive = true,
                )
            }

            if (onNavigateToStorage != null) {
                SettingsDivider()

                SettingsSection(title = stringResource(Res.string.common_storage)) {
                    SettingsNavigationItem(
                        title = stringResource(Res.string.settings_manage_storage),
                        description = stringResource(Res.string.settings_view_and_manage_downloaded_audiobooks),
                        onClick = onNavigateToStorage,
                    )
                }
            }

            SettingsDivider()

            // About section
            SettingsSection(title = stringResource(Res.string.common_about)) {
                SettingsInfoItem(
                    title = stringResource(Res.string.settings_app_version),
                    value = stringResource(Res.string.settings_desktop),
                )
                state.serverVersion?.let { version ->
                    SettingsInfoItem(
                        title = stringResource(Res.string.settings_server_version),
                        value = version,
                    )
                }
                if (onNavigateToLicenses != null) {
                    SettingsNavigationItem(
                        title = stringResource(Res.string.settings_open_source_licenses),
                        description = stringResource(Res.string.settings_view_thirdparty_licenses),
                        onClick = onNavigateToLicenses,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        content()
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 8.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun SettingsToggleItem(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.padding(horizontal = 8.dp),
    )
}

/**
 * Settings item that displays info without interaction.
 */
@Composable
private fun SettingsInfoItem(
    title: String,
    value: String,
) {
    ListItem(
        headlineContent = { Text(title) },
        trailingContent = {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.padding(horizontal = 8.dp),
    )
}

/**
 * Settings item that navigates to another screen.
 */
@Composable
private fun SettingsNavigationItem(
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        trailingContent = {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier =
            Modifier
                .padding(horizontal = 8.dp)
                .clickable(onClick = onClick),
    )
}

/**
 * Settings item for actions (like sign out) with optional icon.
 */
@Composable
private fun SettingsActionItem(
    title: String,
    icon: ImageVector? = null,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    val contentColor =
        if (destructive) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurface
        }

    ListItem(
        headlineContent = {
            Text(
                text = title,
                color = contentColor,
            )
        },
        leadingContent =
            icon?.let {
                {
                    Icon(
                        imageVector = it,
                        contentDescription = null,
                        tint = contentColor,
                    )
                }
            },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier =
            Modifier
                .padding(horizontal = 8.dp)
                .clickable(onClick = onClick),
    )
}

/**
 * Settings item with dropdown selection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> SettingsDropdownItem(
    title: String,
    description: String,
    selectedValue: T,
    options: List<T>,
    formatValue: (T) -> String,
    onValueSelected: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        trailingContent = {
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it },
            ) {
                OutlinedTextField(
                    value = formatValue(selectedValue),
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                    },
                    modifier =
                        Modifier
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                            .width(100.dp),
                    singleLine = true,
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    options.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(formatValue(option)) },
                            onClick = {
                                onValueSelected(option)
                                expanded = false
                            },
                            contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                        )
                    }
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.padding(horizontal = 8.dp),
    )
}
