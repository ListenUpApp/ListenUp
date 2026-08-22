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
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.outlined.HowToReg
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.api.dto.NotificationPreferenceDto
import com.calypsan.listenup.api.notifications.NotificationPreference
import com.calypsan.listenup.client.design.components.FullScreenLoadingIndicator
import com.calypsan.listenup.client.design.components.ListenUpScaffold
import com.calypsan.listenup.client.design.components.SectionGroup
import com.calypsan.listenup.client.design.components.SettingRow
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.features.notifications.notificationTypeNameRes
import com.calypsan.listenup.client.presentation.error.localized
import com.calypsan.listenup.client.presentation.notifications.NotificationPrefsUiState
import com.calypsan.listenup.client.presentation.notifications.NotificationPrefsViewModel
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_back
import listenup.composeapp.generated.resources.common_retry
import listenup.composeapp.generated.resources.notifications_settings_in_app
import listenup.composeapp.generated.resources.notifications_settings_push
import listenup.composeapp.generated.resources.notifications_settings_row_title
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/** Max readable content width — wide windows centre the settings column rather than stretch it. */
private val ContentMaxWidth = 640.dp

/**
 * Per-type notification delivery toggles, rendered from the registry — a new type gets its row
 * with NO edit here (the copy-completeness test forces the string; the registry forces the row).
 * Toggles apply optimistically; the ViewModel reverts them if the server refuses.
 *
 * @param onNavigateBack Navigate back to Settings.
 * @param modifier Modifier for the screen scaffold.
 * @param viewModel The preferences ViewModel, provided via Koin.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSettingsScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NotificationPrefsViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    ListenUpScaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.notifications_settings_row_title)) },
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
        when (val s = state) {
            is NotificationPrefsUiState.Loading -> {
                FullScreenLoadingIndicator(modifier = Modifier.padding(padding))
            }

            is NotificationPrefsUiState.Error -> {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(horizontal = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            text = s.error.localized(),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                        Button(onClick = viewModel::refresh) {
                            Text(stringResource(Res.string.common_retry))
                        }
                    }
                }
            }

            is NotificationPrefsUiState.Data -> {
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
                    ) {
                        SectionGroup(
                            icon = Icons.Default.Notifications,
                            label = stringResource(Res.string.notifications_settings_row_title),
                            accent = MaterialTheme.colorScheme.primary,
                        ) {
                            // Unknown type keys get no row — a newer server's types wait for the
                            // client update; there is nothing to toggle blind.
                            val knownPrefs =
                                s.prefs.filter { notificationTypeNameRes(it.type) != null }
                            knownPrefs.forEachIndexed { index, pref ->
                                NotificationPrefRow(
                                    pref = pref,
                                    showDivider = index > 0,
                                    onChange = { viewModel.setPreference(pref.type, it) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One registry type's row: display name + two labeled switches (In-app, Push). The Push switch is
 * disabled for types the registry declares push-ineligible.
 */
@Composable
private fun NotificationPrefRow(
    pref: NotificationPreferenceDto,
    showDivider: Boolean,
    onChange: (NotificationPreference) -> Unit,
    modifier: Modifier = Modifier,
) {
    val nameRes = notificationTypeNameRes(pref.type) ?: return
    val haptics = LocalHaptics.current
    SettingRow(
        title = stringResource(nameRes),
        icon = notificationTypeIcon(pref.type),
        showDivider = showDivider,
        modifier = modifier,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LabeledSwitch(
                label = stringResource(Res.string.notifications_settings_in_app),
                checked = pref.preference.inApp,
                onCheckedChange = { checked ->
                    haptics.toggle(on = checked)
                    onChange(pref.preference.copy(inApp = checked))
                },
            )
            LabeledSwitch(
                label = stringResource(Res.string.notifications_settings_push),
                checked = pref.preference.push,
                enabled = pref.pushEligible,
                onCheckedChange = { checked ->
                    haptics.toggle(on = checked)
                    onChange(pref.preference.copy(push = checked))
                },
            )
        }
    }
}

/** A switch with its delivery-channel label above it, so the dual-switch row reads at a glance. */
@Composable
private fun LabeledSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

/** The leading tile glyph for a registry type key; mirrors the inbox's per-event icons. */
private fun notificationTypeIcon(type: String): ImageVector =
    when (type) {
        "campfire_invite" -> Icons.Outlined.LocalFireDepartment
        "registration_decision" -> Icons.Outlined.HowToReg
        "registration_approval" -> Icons.Outlined.PersonAdd
        else -> Icons.Outlined.Notifications
    }
