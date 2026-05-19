package com.calypsan.listenup.client.design.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * ListenUp styled alert dialog with consistent Material 3 Expressive styling.
 *
 * Uses the theme's large shape (28dp rounded corners) and proper surface colors
 * for a cohesive look across the app.
 *
 * @param onDismissRequest Called when the dialog is dismissed (back press, outside tap).
 * @param title The dialog title.
 * @param text The dialog message body.
 * @param confirmText Text for the confirm button.
 * @param onConfirm Called when confirm is clicked.
 * @param dismissText Text for the dismiss button. If null, no dismiss button is shown.
 * @param onDismiss Called when dismiss button is clicked.
 * @param icon Optional icon displayed above the title.
 * @param confirmColor Color for the confirm button text. Defaults to primary.
 */
@Composable
fun ListenUpAlertDialog(
    onDismissRequest: () -> Unit,
    title: String,
    text: String,
    confirmText: String,
    onConfirm: () -> Unit,
    dismissText: String? = "Cancel",
    onDismiss: (() -> Unit)? = null,
    icon: ImageVector? = null,
    confirmColor: Color = MaterialTheme.colorScheme.primary,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        shape = MaterialTheme.shapes.large,
        containerColor = MaterialTheme.colorScheme.surface,
        icon = icon?.let { { Icon(it, contentDescription = null) } },
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmText, color = confirmColor)
            }
        },
        dismissButton =
            if (dismissText != null && onDismiss != null) {
                {
                    TextButton(onClick = onDismiss) {
                        Text(dismissText)
                    }
                }
            } else {
                null
            },
    )
}

/**
 * Convenience dialog for destructive actions (delete, revoke, discard).
 *
 * Pre-configured with error color for confirm button to indicate danger.
 */
@Composable
fun ListenUpDestructiveDialog(
    onDismissRequest: () -> Unit,
    title: String,
    text: String,
    confirmText: String = "Delete",
    onConfirm: () -> Unit,
    dismissText: String = "Cancel",
    onDismiss: () -> Unit = onDismissRequest,
    icon: ImageVector? = null,
) {
    ListenUpAlertDialog(
        onDismissRequest = onDismissRequest,
        title = title,
        text = text,
        confirmText = confirmText,
        onConfirm = onConfirm,
        dismissText = dismissText,
        onDismiss = onDismiss,
        icon = icon,
        confirmColor = MaterialTheme.colorScheme.error,
    )
}
