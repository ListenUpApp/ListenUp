package com.calypsan.listenup.client.features.seriesedit.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.ListenUpAlertDialog
import com.calypsan.listenup.client.design.components.ListenUpTextField
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.presentation.seriesedit.NewSeriesDraft
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_cancel
import listenup.composeapp.generated.resources.series_name_exists_inline
import listenup.composeapp.generated.resources.series_series_name
import org.jetbrains.compose.resources.stringResource

/**
 * "Name a new series" — shared by "New series" (inside this one) and "New parent series".
 *
 * [body] says what Create will do. When the name is already taken the dialog says so in place of
 * [body] and — if that series can be used — offers [useExistingLabel] instead of failing on the
 * server with a duplicate.
 */
@Composable
internal fun SeriesNameDialog(
    title: String,
    draft: NewSeriesDraft,
    body: String,
    confirmLabel: String,
    useExistingLabel: String,
    onNameChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onUseExisting: (seriesId: String) -> Unit,
    onDismiss: () -> Unit,
    busy: Boolean = false,
) {
    val haptics = LocalHaptics.current
    val existing = draft.existing
    ListenUpAlertDialog(
        onDismissRequest = onDismiss,
        title = title,
        confirmText = confirmLabel,
        onConfirm = onConfirm,
        dismissText = stringResource(Res.string.common_cancel),
        onDismiss = onDismiss,
        confirmEnabled = draft.canCreate,
        confirmBusy = busy,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ListenUpTextField(
                value = draft.name,
                onValueChange = onNameChange,
                label = stringResource(Res.string.series_series_name),
                shape = OutlinedTextFieldDefaults.shape,
                isError = existing != null,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions =
                    KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (draft.canCreate) onConfirm() }),
            )
            if (existing == null) {
                if (draft.name.isNotBlank()) {
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    text = stringResource(Res.string.series_name_exists_inline, existing.name),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                if (existing.isSelectable) {
                    TextButton(
                        onClick = {
                            haptics.press()
                            onUseExisting(existing.id)
                        },
                    ) {
                        Text(useExistingLabel)
                    }
                }
            }
        }
    }
}
