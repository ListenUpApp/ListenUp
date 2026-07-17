package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_restart_book
import listenup.composeapp.generated.resources.book_detail_restart_prompt
import listenup.composeapp.generated.resources.common_cancel
import org.jetbrains.compose.resources.stringResource

/**
 * Confirmation for "Restart Book" — the action resets the book's position to the start.
 *
 * It is destructive: the current position is discarded, so a mis-tap costs the user their place.
 * A confirmation guards it, mirroring [MarkNotStartedDialog] so both progress-affecting overflow
 * actions share the same safety bar.
 */
@Composable
fun RestartBookDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.book_detail_restart_book)) },
        text = { Text(stringResource(Res.string.book_detail_restart_prompt)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(Res.string.book_detail_restart_book),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.common_cancel))
            }
        },
    )
}
