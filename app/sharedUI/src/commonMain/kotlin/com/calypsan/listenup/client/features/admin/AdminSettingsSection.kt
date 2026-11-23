package com.calypsan.listenup.client.features.admin

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import com.calypsan.listenup.client.design.components.ForwardEdits
import androidx.compose.foundation.text.input.TextFieldState
import com.calypsan.listenup.client.design.components.SettingToggleRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.api.dto.admin.RatingSourceStatus
import com.calypsan.listenup.api.dto.admin.RatingSourceUnavailable
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.sync.ExternalRatingSource
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicatorSmall
import com.calypsan.listenup.client.design.components.ListenUpTextField
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.components.SectionGroup
import com.calypsan.listenup.client.design.components.SectionSegment
import com.calypsan.listenup.client.design.icons.ListenUpIcons
import com.calypsan.listenup.client.design.util.ratingSourceLabel
import com.calypsan.listenup.client.design.util.isJustNow
import com.calypsan.listenup.client.design.util.relativeTime
import com.calypsan.listenup.client.util.formatDateLong
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.presentation.admin.AdminUiState
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.admin_inbox_setting_subtitle
import listenup.composeapp.generated.resources.admin_inbox_setting_title
import listenup.composeapp.generated.resources.admin_registration_approval_desc
import listenup.composeapp.generated.resources.admin_registration_closed_desc
import listenup.composeapp.generated.resources.admin_registration_open_desc
import listenup.composeapp.generated.resources.admin_registration_policy
import listenup.composeapp.generated.resources.admin_registration_policy_approval
import listenup.composeapp.generated.resources.admin_registration_policy_closed
import listenup.composeapp.generated.resources.admin_registration_policy_open
import listenup.composeapp.generated.resources.admin_push_setting_subtitle
import listenup.composeapp.generated.resources.admin_push_setting_title
import listenup.composeapp.generated.resources.admin_rating_source_error
import listenup.composeapp.generated.resources.admin_rating_source_last_fetched
import listenup.composeapp.generated.resources.admin_rating_source_last_fetched_just_now
import listenup.composeapp.generated.resources.admin_rating_source_never_fetched
import listenup.composeapp.generated.resources.admin_rating_source_no_connection
import listenup.composeapp.generated.resources.admin_rating_source_not_configured
import listenup.composeapp.generated.resources.admin_rating_source_paused
import listenup.composeapp.generated.resources.admin_rating_source_paused_plain
import listenup.composeapp.generated.resources.admin_rating_source_unavailable
import listenup.composeapp.generated.resources.admin_rating_source_using_connection
import listenup.composeapp.generated.resources.admin_rating_sources_hint
import listenup.composeapp.generated.resources.admin_rating_sources_title
import listenup.composeapp.generated.resources.admin_remote_url
import listenup.composeapp.generated.resources.admin_remote_url_placeholder
import listenup.composeapp.generated.resources.admin_server_name
import listenup.composeapp.generated.resources.admin_server_settings
import listenup.composeapp.generated.resources.connect_listenup_server
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
// ---------------------------------------------------------------------------
// Server settings section
// ---------------------------------------------------------------------------

@Composable
internal fun ServerSettingsSection(
    state: AdminUiState.Ready,
    serverName: String,
    onServerNameChange: (String) -> Unit,
    remoteUrl: String,
    onRemoteUrlChange: (String) -> Unit,
    serverSettingsLoaded: Boolean,
    holdNewBooksForReview: Boolean,
    onHoldNewBooksForReviewChange: (Boolean) -> Unit,
    pushNotificationsEnabled: Boolean,
    onPushNotificationsEnabledChange: (Boolean) -> Unit,
    onRegistrationPolicyChange: (RegistrationPolicy) -> Unit,
) {
    // The settings load on their own clock, so the fields may be on screen before the text exists.
    // Re-seeding when they land is a fresh state, not an edit: the ViewModel is not told, so loading
    // never marks the form dirty.
    val serverNameState = remember(serverSettingsLoaded) { TextFieldState(serverName) }
    val remoteUrlState = remember(serverSettingsLoaded) { TextFieldState(remoteUrl) }
    serverNameState.ForwardEdits(onServerNameChange)
    remoteUrlState.ForwardEdits(onRemoteUrlChange)
    SectionGroup(
        label = stringResource(Res.string.admin_server_settings),
    ) {
        SectionSegment {
            Column(
                modifier = Modifier.padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ListenUpTextField(
                    state = serverNameState,
                    label = stringResource(Res.string.admin_server_name),
                    placeholder = stringResource(Res.string.connect_listenup_server),
                    leadingIcon = ListenUpIcons.Badge,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                )
                ListenUpTextField(
                    state = remoteUrlState,
                    label = stringResource(Res.string.admin_remote_url),
                    placeholder = stringResource(Res.string.admin_remote_url_placeholder),
                    leadingIcon = ListenUpIcons.CloudDownload,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
            }
        }
        SectionSegment {
            RegistrationPolicyControl(
                policy = state.registrationPolicy,
                isToggling = state.isTogglingRegistrationPolicy,
                onChange = onRegistrationPolicyChange,
            )
        }
        SettingToggleRow(
            icon = ListenUpIcons.Inbox,
            title = stringResource(Res.string.admin_inbox_setting_title),
            subtitle = stringResource(Res.string.admin_inbox_setting_subtitle),
            checked = holdNewBooksForReview,
            onCheckedChange = onHoldNewBooksForReviewChange,
        )
        SettingToggleRow(
            icon = ListenUpIcons.Notifications,
            title = stringResource(Res.string.admin_push_setting_title),
            subtitle = stringResource(Res.string.admin_push_setting_subtitle),
            checked = pushNotificationsEnabled,
            onCheckedChange = onPushNotificationsEnabledChange,
        )
    }
}

/**
 * Three-state registration control (Open / Approval / Closed) backed by the server's
 * [RegistrationPolicy]. A single segmented selector — not a boolean switch — so all three
 * states are visible and round-trip correctly. The subtitle reflects the *current* policy.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RegistrationPolicyControl(
    policy: RegistrationPolicy,
    isToggling: Boolean,
    onChange: (RegistrationPolicy) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHaptics.current
    val options = listOf(RegistrationPolicy.OPEN, RegistrationPolicy.APPROVAL_QUEUE, RegistrationPolicy.CLOSED)
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = ListenUpIcons.HowToReg,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.admin_registration_policy),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(registrationPolicyDescription(policy)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isToggling) {
                ListenUpLoadingIndicatorSmall()
            }
        }
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = policy == option,
                    onClick = {
                        if (policy != option) {
                            haptics.selectionTick()
                            onChange(option)
                        }
                    },
                    enabled = !isToggling,
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                ) {
                    Text(stringResource(registrationPolicyLabel(option)))
                }
            }
        }
    }
}

private fun registrationPolicyLabel(policy: RegistrationPolicy): StringResource =
    when (policy) {
        RegistrationPolicy.OPEN -> Res.string.admin_registration_policy_open
        RegistrationPolicy.APPROVAL_QUEUE -> Res.string.admin_registration_policy_approval
        RegistrationPolicy.CLOSED -> Res.string.admin_registration_policy_closed
    }

private fun registrationPolicyDescription(policy: RegistrationPolicy): StringResource =
    when (policy) {
        RegistrationPolicy.OPEN -> Res.string.admin_registration_open_desc
        RegistrationPolicy.APPROVAL_QUEUE -> Res.string.admin_registration_approval_desc
        RegistrationPolicy.CLOSED -> Res.string.admin_registration_closed_desc
    }

// ---------------------------------------------------------------------------
// Rating sources section
// ---------------------------------------------------------------------------

/**
 * "Rating sources": one row per outside catalog the server can fetch a book's rating from, a
 * switch to enable/disable it, and a health line reporting its last fetch. Switching a source off
 * hides its scores at once everywhere (each of its rows flips `enabled`); switching it back on
 * brings them back.
 */
@Composable
internal fun RatingSourcesGroup(
    sources: List<RatingSourceStatus>,
    onSourceEnabledChange: (ExternalRatingSource, Boolean) -> Unit,
) {
    SectionGroup(
        label = stringResource(Res.string.admin_rating_sources_title),
    ) {
        SectionSegment {
            Text(
                text = stringResource(Res.string.admin_rating_sources_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(Spacing.lg),
            )
        }
        sources.forEach { status ->
            RatingSourceRow(
                status = status,
                onEnabledChange = { enabled -> onSourceEnabledChange(status.source, enabled) },
            )
        }
    }
}

/**
 * One source: the whole row is its switch, and the subtitle says how it is doing. The health line
 * reads, first match wins: why it cannot run at all, then until when it has paused itself (a
 * future date, so an absolute one — "Paused until October 6, 2026"), then its last error, then
 * when it last fetched, then that it never has. Hardcover adds whose account it fetches with.
 * An unavailable source's switch stays operable: turning it off is still meaningful.
 */
@Composable
private fun RatingSourceRow(
    status: RatingSourceStatus,
    onEnabledChange: (Boolean) -> Unit,
) {
    val healthLine = ratingSourceHealthLine(status)
    val connectionLine =
        status.connectionUsername
            ?.takeIf { status.source == ExternalRatingSource.HARDCOVER }
            ?.let { stringResource(Res.string.admin_rating_source_using_connection, it) }
    SettingToggleRow(
        icon = ListenUpIcons.Star,
        title = ratingSourceLabel(status.source),
        subtitle = listOfNotNull(healthLine, connectionLine).joinToString("\n"),
        checked = status.enabled,
        onCheckedChange = onEnabledChange,
        modifier = Modifier.testTag("ratingSourceSwitch_${status.source.name}"),
    )
}

/** [status]'s one-line health, in the priority [RatingSourceRow] documents. */
@Composable
private fun ratingSourceHealthLine(status: RatingSourceStatus): String {
    val unavailable = status.unavailable
    val pausedUntil = status.pausedUntil
    val lastError = status.lastError
    val lastFetchedAt = status.lastFetchedAt
    return when {
        unavailable != null -> {
            stringResource(
                when (unavailable) {
                    RatingSourceUnavailable.NOT_CONFIGURED -> Res.string.admin_rating_source_not_configured
                    RatingSourceUnavailable.NO_CONNECTION -> Res.string.admin_rating_source_no_connection
                    RatingSourceUnavailable.UNKNOWN -> Res.string.admin_rating_source_unavailable
                },
            )
        }

        pausedUntil != null && lastError != null -> {
            stringResource(Res.string.admin_rating_source_paused, formatDateLong(pausedUntil), lastError)
        }

        pausedUntil != null -> {
            stringResource(Res.string.admin_rating_source_paused_plain, formatDateLong(pausedUntil))
        }

        lastError != null -> {
            stringResource(Res.string.admin_rating_source_error, lastError)
        }

        lastFetchedAt != null && isJustNow(lastFetchedAt) -> {
            stringResource(Res.string.admin_rating_source_last_fetched_just_now)
        }

        lastFetchedAt != null -> {
            stringResource(Res.string.admin_rating_source_last_fetched, relativeTime(lastFetchedAt))
        }

        else -> {
            stringResource(Res.string.admin_rating_source_never_fetched)
        }
    }
}
