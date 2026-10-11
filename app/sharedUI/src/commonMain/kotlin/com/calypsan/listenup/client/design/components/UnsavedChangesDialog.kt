package com.calypsan.listenup.client.design.components

import androidx.compose.runtime.Composable
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_edit_keep_editing
import listenup.composeapp.generated.resources.book_edit_unsaved_changes
import listenup.composeapp.generated.resources.book_edit_you_have_unsaved_changes_are
import listenup.composeapp.generated.resources.common_discard
import org.jetbrains.compose.resources.stringResource

/**
 * The question every edit screen asks before it throws a draft away: discard it, or keep editing.
 *
 * @param onDiscard the user chose to leave and lose the draft.
 * @param onKeepEditing the user chose to stay (also fired by tapping outside or pressing Back).
 */
@Composable
fun UnsavedChangesDialog(
    onDiscard: () -> Unit,
    onKeepEditing: () -> Unit,
) {
    ListenUpDestructiveDialog(
        onDismissRequest = onKeepEditing,
        title = stringResource(Res.string.book_edit_unsaved_changes),
        text = stringResource(Res.string.book_edit_you_have_unsaved_changes_are),
        confirmText = stringResource(Res.string.common_discard),
        onConfirm = onDiscard,
        dismissText = stringResource(Res.string.book_edit_keep_editing),
        onDismiss = onKeepEditing,
    )
}
