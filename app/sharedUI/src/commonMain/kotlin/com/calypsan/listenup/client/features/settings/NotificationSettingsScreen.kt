package com.calypsan.listenup.client.features.settings

import com.calypsan.listenup.client.design.components.ListenUpTopAppBar
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
import androidx.compose.material.icons.outlined.HowToReg
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.calypsan.listenup.client.design.components.switchRow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.api.dto.NotificationPreferenceDto
import com.calypsan.listenup.api.notifications.NotificationPreference
import com.calypsan.listenup.client.design.components.FullScreenLoadingIndicator
import com.calypsan.listenup.client.design.components.ListenUpScaffold
import com.calypsan.listenup.client.design.components.SectionColumns
import com.calypsan.listenup.client.design.components.SectionGroup
import com.calypsan.listenup.client.design.components.SectionSegment
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.window.core.layout.WindowSizeClass
import com.calypsan.listenup.client.design.components.SettingRow
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.features.notifications.notificationTypeNameRes
import com.calypsan.listenup.client.presentation.error.localized
import com.calypsan.listenup.client.presentation.notifications.NotificationPrefsUiState
import com.calypsan.listenup.client.presentation.notifications.NotificationPrefsViewModel
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_retry
import listenup.composeapp.generated.resources.notifications_settings_in_app
import listenup.composeapp.generated.resources.notifications_settings_push
import listenup.composeapp.generated.resources.notifications_settings_switch_a11y
import listenup.composeapp.generated.resources.notifications_settings_row_title
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

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
            ListenUpTopAppBar(
                title = stringResource(Res.string.notifications_settings_row_title),
                onBack = onNavigateBack,
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
                            .padding(horizontal = Spacing.screenMargin),
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
                NotificationPrefsContent(
                    prefs = s.prefs,
                    onChange = viewModel::setPreference,
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }
}

/**
 * The per-type delivery toggles. Unknown type keys get no row — a newer server's types wait for the
 * client update; there is nothing to toggle blind.
 *
 * A phone lists the types as rows of one group. From the medium width up each type becomes its own
 * card in [SectionColumns], its two channel switches side by side under its name, so the choices
 * spread across a tablet instead of trailing a long way from the names they belong to.
 */
@Composable
internal fun NotificationPrefsContent(
    prefs: List<NotificationPreferenceDto>,
    onChange: (type: String, preference: NotificationPreference) -> Unit,
    modifier: Modifier = Modifier,
) {
    val knownPrefs = prefs.filter { notificationTypeNameRes(it.type) != null }
    val isWide =
        currentWindowAdaptiveInfoV2().windowSizeClass.isWidthAtLeastBreakpoint(
            WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND,
        )
    if (isWide) {
        SectionColumns(
            modifier =
                modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.screenMargin, vertical = 16.dp),
        ) {
            knownPrefs.forEach { pref ->
                section { NotificationPrefCard(pref = pref, onChange = { onChange(pref.type, it) }) }
            }
        }
    } else {
        Column(
            modifier =
                modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        ) {
            SectionGroup(
                label = stringResource(Res.string.notifications_settings_row_title),
            ) {
                knownPrefs.forEach { pref ->
                    NotificationPrefRow(
                        pref = pref,
                        onChange = { onChange(pref.type, it) },
                    )
                }
            }
        }
    }
}

/** One registry type as a wide-layout card: the type heads the group, its channel switches below. */
@Composable
private fun NotificationPrefCard(
    pref: NotificationPreferenceDto,
    onChange: (NotificationPreference) -> Unit,
) {
    val nameRes = notificationTypeNameRes(pref.type) ?: return
    val typeName = stringResource(nameRes)
    SectionGroup(
        label = typeName,
    ) {
        SectionSegment {
            ChannelSwitches(
                pref = pref,
                typeName = typeName,
                onChange = onChange,
                modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            )
        }
    }
}

/**
 * One registry type's row: display name + two labeled switches (In-app, Push). The Push switch is
 * disabled for types the registry declares push-ineligible.
 */
@Composable
internal fun NotificationPrefRow(
    pref: NotificationPreferenceDto,
    onChange: (NotificationPreference) -> Unit,
    modifier: Modifier = Modifier,
) {
    val nameRes = notificationTypeNameRes(pref.type) ?: return
    val typeName = stringResource(nameRes)
    SettingRow(
        title = typeName,
        icon = notificationTypeIcon(pref.type),
        modifier = modifier,
    ) {
        ChannelSwitches(pref = pref, typeName = typeName, onChange = onChange)
    }
}

/**
 * A type's In-app and Push switches, side by side. The Push switch is disabled for types the
 * registry declares push-ineligible.
 */
@Composable
private fun ChannelSwitches(
    pref: NotificationPreferenceDto,
    typeName: String,
    onChange: (NotificationPreference) -> Unit,
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(16.dp),
) {
    Row(modifier = modifier, horizontalArrangement = horizontalArrangement) {
        LabeledSwitch(
            label = stringResource(Res.string.notifications_settings_in_app),
            typeName = typeName,
            checked = pref.preference.inApp,
            onCheckedChange = { checked -> onChange(pref.preference.copy(inApp = checked)) },
        )
        LabeledSwitch(
            label = stringResource(Res.string.notifications_settings_push),
            typeName = typeName,
            checked = pref.preference.push,
            enabled = pref.pushEligible,
            onCheckedChange = { checked -> onChange(pref.preference.copy(push = checked)) },
        )
    }
}

/**
 * A switch with its delivery-channel label above it, so the dual-switch row reads at a glance. Label
 * and switch are one [switchRow] target, and TalkBack hears the channel AND the type ("Push
 * notifications for Campfire invites") — two bare "Push" switches per screen would be ambiguous.
 */
@Composable
private fun LabeledSwitch(
    label: String,
    typeName: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val haptics = LocalHaptics.current
    val description = stringResource(Res.string.notifications_settings_switch_a11y, label, typeName)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier =
            modifier
                .switchRow(
                    checked = checked,
                    haptics = haptics,
                    enabled = enabled,
                    onCheckedChange = onCheckedChange,
                ).semantics { contentDescription = description },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // The visible label is already inside the description; don't read it twice.
            modifier = Modifier.clearAndSetSemantics {},
        )
        Switch(
            checked = checked,
            onCheckedChange = null,
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
