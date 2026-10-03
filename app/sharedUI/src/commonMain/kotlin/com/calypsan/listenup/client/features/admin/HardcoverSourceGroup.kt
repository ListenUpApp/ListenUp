package com.calypsan.listenup.client.features.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.calypsan.listenup.api.dto.admin.HardcoverApiTokenStatus
import com.calypsan.listenup.api.dto.admin.HardcoverSourceStatus
import com.calypsan.listenup.client.design.components.ListenUpButton
import com.calypsan.listenup.client.design.components.ListenUpDestructiveDialog
import com.calypsan.listenup.client.design.components.ListenUpTextField
import com.calypsan.listenup.client.design.components.SectionGroup
import com.calypsan.listenup.client.design.components.SectionSegment
import com.calypsan.listenup.client.design.components.SettingRow
import com.calypsan.listenup.client.design.components.SettingToggleRow
import com.calypsan.listenup.client.design.components.passwordVisibilityDescription
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.presentation.admin.HardcoverTokenSave
import com.calypsan.listenup.client.presentation.error.localized
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.admin_hardcover_hint
import listenup.composeapp.generated.resources.admin_hardcover_metadata_subtitle
import listenup.composeapp.generated.resources.admin_hardcover_metadata_title
import listenup.composeapp.generated.resources.admin_hardcover_metadata_unavailable
import listenup.composeapp.generated.resources.admin_hardcover_token_checking
import listenup.composeapp.generated.resources.admin_hardcover_token_get
import listenup.composeapp.generated.resources.admin_hardcover_token_label
import listenup.composeapp.generated.resources.admin_hardcover_token_placeholder
import listenup.composeapp.generated.resources.admin_hardcover_token_rejected
import listenup.composeapp.generated.resources.admin_hardcover_token_remove_body
import listenup.composeapp.generated.resources.admin_hardcover_token_remove_label
import listenup.composeapp.generated.resources.admin_hardcover_token_remove_title
import listenup.composeapp.generated.resources.admin_hardcover_token_replace
import listenup.composeapp.generated.resources.admin_hardcover_token_replace_label
import listenup.composeapp.generated.resources.admin_hardcover_token_save
import listenup.composeapp.generated.resources.admin_hardcover_token_set
import listenup.composeapp.generated.resources.common_open_in_browser
import listenup.composeapp.generated.resources.common_remove
import listenup.composeapp.generated.resources.rating_source_hardcover
import org.jetbrains.compose.resources.stringResource

/** Hardcover's API page, where an admin gets a token. */
private const val HARDCOVER_API_PAGE = "https://hardcover.app/account/api"

/** What the Hardcover section can ask for. The defaults do nothing (desktop, previews). */
data class HardcoverSourceActions(
    val onSaveToken: (String) -> Unit = {},
    val onRemoveToken: () -> Unit = {},
    val onMetadataEnabledChange: (Boolean) -> Unit = {},
    val onClearTokenError: () -> Unit = {},
)

/**
 * Admin → Hardcover (#1542): the write-only API token, where to get one, and the "Hardcover metadata"
 * switch. The token is typed into this composable's own state — `remember`, never `rememberSaveable`, so
 * it is not written into saved instance state — sent once on Save, and dropped when Hardcover accepts it.
 */
@Composable
internal fun HardcoverSourceGroup(
    status: HardcoverSourceStatus,
    tokenSave: HardcoverTokenSave,
    actions: HardcoverSourceActions,
) {
    var replacing by remember { mutableStateOf(false) }
    var confirmingRemove by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    LaunchedEffect(status.apiToken) {
        if (status.apiToken is HardcoverApiTokenStatus.Saved) replacing = false
    }

    SectionGroup(label = stringResource(Res.string.rating_source_hardcover)) {
        SectionSegment {
            Text(
                text = stringResource(Res.string.admin_hardcover_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(Spacing.lg),
            )
        }
        val token = status.apiToken
        if (token is HardcoverApiTokenStatus.Saved && !replacing) {
            SavedToken(
                username = token.username,
                busy = tokenSave == HardcoverTokenSave.Busy,
                onReplace = { replacing = true },
                onRemove = { confirmingRemove = true },
            )
        } else {
            TokenEntry(rejected = token is HardcoverApiTokenStatus.Rejected, tokenSave = tokenSave, actions = actions)
        }
        SettingRow(
            title = stringResource(Res.string.admin_hardcover_token_get),
            icon = Icons.AutoMirrored.Outlined.OpenInNew,
            onClick = { uriHandler.openUri(HARDCOVER_API_PAGE) },
            onClickLabel = stringResource(Res.string.common_open_in_browser),
        )
        SettingToggleRow(
            icon = Icons.Outlined.Category,
            title = stringResource(Res.string.admin_hardcover_metadata_title),
            subtitle =
                stringResource(
                    if (status.metadataUnavailable != null) {
                        Res.string.admin_hardcover_metadata_unavailable
                    } else {
                        Res.string.admin_hardcover_metadata_subtitle
                    },
                ),
            checked = status.metadataEnabled,
            onCheckedChange = actions.onMetadataEnabledChange,
            modifier = Modifier.testTag("hardcoverMetadataSwitch"),
        )
    }

    if (confirmingRemove) {
        ListenUpDestructiveDialog(
            onDismissRequest = { confirmingRemove = false },
            title = stringResource(Res.string.admin_hardcover_token_remove_title),
            text = stringResource(Res.string.admin_hardcover_token_remove_body),
            confirmText = stringResource(Res.string.common_remove),
            onConfirm = {
                confirmingRemove = false
                actions.onRemoveToken()
            },
        )
    }
}

/** "Set · belongs to @simon", with Replace and Remove. The token itself is never shown again. */
@Composable
private fun SavedToken(
    username: String,
    busy: Boolean,
    onReplace: () -> Unit,
    onRemove: () -> Unit,
) {
    SectionSegment {
        Column(modifier = Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Text(
                text = stringResource(Res.string.admin_hardcover_token_set, username),
                style = MaterialTheme.typography.bodyLarge,
            )
            // Flows, so at a large font Remove wraps beneath Replace instead of being squeezed to "Re".
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                val replaceLabel = stringResource(Res.string.admin_hardcover_token_replace_label)
                val removeLabel = stringResource(Res.string.admin_hardcover_token_remove_label)
                ListenUpButton(
                    text = stringResource(Res.string.admin_hardcover_token_replace),
                    onClick = onReplace,
                    filled = false,
                    fillMaxWidth = false,
                    modifier = Modifier.semantics { contentDescription = replaceLabel },
                )
                ListenUpButton(
                    text = stringResource(Res.string.common_remove),
                    onClick = onRemove,
                    enabled = !busy,
                    filled = false,
                    fillMaxWidth = false,
                    danger = true,
                    modifier = Modifier.testTag("hardcoverTokenRemove").semantics { contentDescription = removeLabel },
                )
            }
        }
    }
}

/** The secure field and its Save button; a rejected token's line above, a refusal beneath. */
@Composable
private fun TokenEntry(
    rejected: Boolean,
    tokenSave: HardcoverTokenSave,
    actions: HardcoverSourceActions,
) {
    var draft by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val busy = tokenSave == HardcoverTokenSave.Busy
    val refusal = (tokenSave as? HardcoverTokenSave.Refused)?.error?.localized()
    SectionSegment {
        Column(modifier = Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            if (rejected) {
                Text(
                    text = stringResource(Res.string.admin_hardcover_token_rejected),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            ListenUpTextField(
                value = draft,
                onValueChange = {
                    draft = it
                    if (refusal != null) actions.onClearTokenError()
                },
                label = stringResource(Res.string.admin_hardcover_token_label),
                placeholder = stringResource(Res.string.admin_hardcover_token_placeholder),
                enabled = !busy,
                isError = refusal != null,
                supportingText = refusal,
                leadingIcon = Icons.Outlined.Lock,
                trailingIcon = if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                onTrailingClick = { visible = !visible },
                trailingIconContentDescription = passwordVisibilityDescription(visible),
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Done,
                    ),
                keyboardActions =
                    KeyboardActions(onDone = {
                        if (draft.isNotBlank() &&
                            !busy
                        ) {
                            actions.onSaveToken(draft)
                        }
                    }),
                modifier = Modifier.testTag("hardcoverTokenField"),
            )
            if (busy) {
                Text(
                    text = stringResource(Res.string.admin_hardcover_token_checking),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            ListenUpButton(
                text = stringResource(Res.string.admin_hardcover_token_save),
                onClick = { actions.onSaveToken(draft) },
                enabled = draft.isNotBlank(),
                isLoading = busy,
                modifier = Modifier.testTag("hardcoverTokenSave"),
            )
        }
    }
}
