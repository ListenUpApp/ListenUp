package com.calypsan.listenup.client.features.contributoredit.components

import com.calypsan.listenup.client.design.components.ListenUpAlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.contributor_rename_collision_body
import listenup.composeapp.generated.resources.contributor_rename_collision_keep_separate
import listenup.composeapp.generated.resources.contributor_rename_collision_merge
import listenup.composeapp.generated.resources.contributor_rename_collision_title
import org.jetbrains.compose.resources.stringResource

/**
 * Prompt shown when the user renames a contributor to a name that matches an existing,
 * different contributor under forgiving punctuation/spacing normalization (e.g. "George
 * R.R. Martin" vs "George R. R. Martin"). The rename is held back until the user picks one:
 * - Merge: folds this contributor into the existing one (which survives under its own name).
 * - Keep separate: proceeds with the rename exactly as typed.
 *
 * Dismissing (back gesture / tap outside) is a no-op cancel — neither action runs and the
 * caller stays on the edit screen with the typed name still unsaved.
 *
 * @param newName The name the user just typed (about to be saved).
 * @param existingName The existing contributor's current name that collided.
 * @param onMerge Called when the user taps "Merge".
 * @param onKeepSeparate Called when the user taps "Keep Separate".
 * @param onDismiss Called when the dialog is dismissed without an explicit choice.
 */
@Composable
fun RenameCollisionDialog(
    newName: String,
    existingName: String,
    onMerge: () -> Unit,
    onKeepSeparate: () -> Unit,
    onDismiss: () -> Unit,
) {
    ListenUpAlertDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.contributor_rename_collision_title),
        confirmText = stringResource(Res.string.contributor_rename_collision_merge),
        onConfirm = onMerge,
        dismissText = stringResource(Res.string.contributor_rename_collision_keep_separate),
        onDismiss = onKeepSeparate,
    ) {
        Text(
            text = stringResource(Res.string.contributor_rename_collision_body, newName, existingName),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
