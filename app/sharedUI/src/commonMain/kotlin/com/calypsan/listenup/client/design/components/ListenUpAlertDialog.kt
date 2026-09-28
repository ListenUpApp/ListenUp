package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.window.DialogProperties
import com.calypsan.listenup.client.design.haptics.LocalHaptics

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
    ListenUpAlertDialog(
        onDismissRequest = onDismissRequest,
        title = title,
        confirmText = confirmText,
        onConfirm = onConfirm,
        dismissText = dismissText,
        onDismiss = onDismiss,
        icon = icon,
        confirmColor = confirmColor,
    ) {
        Text(text)
    }
}

/**
 * [ListenUpAlertDialog] with a composed body — a field, a list, a progress bar — for dialogs whose
 * content is more than one message. Same shape, colours and haptics as the text form: the confirm
 * button commits, the dismiss button presses.
 *
 * @param onDismissRequest Called when the dialog is dismissed (back press, outside tap).
 * @param title The dialog title.
 * @param confirmText Text for the confirm button; null for a dialog with no confirm action (a
 *   picker whose rows are the choices, or a progress report still running).
 * @param onConfirm Called when confirm is clicked. Never called while [confirmEnabled] is false.
 * @param dismissText Text for the dismiss button. If null (or [onDismiss] is null), no dismiss button.
 * @param onDismiss Called when the dismiss button is clicked.
 * @param icon Optional icon displayed above the title.
 * @param confirmColor Color for the confirm button text. Defaults to primary; error for destructive.
 * @param confirmEnabled Whether the confirm action is available (e.g. a required field is filled).
 * @param confirmBusy Shows a small loading indicator in place of the confirm label, disabled.
 * @param dismissEnabled Whether the dismiss button is available.
 * @param dismissIcon Optional leading icon for the dismiss button.
 * @param properties Platform dialog behaviour, e.g. refusing back-press and outside-tap dismissal.
 * @param content The dialog body.
 */
@Composable
fun ListenUpAlertDialog(
    onDismissRequest: () -> Unit,
    title: String,
    confirmText: String?,
    onConfirm: () -> Unit = {},
    dismissText: String? = "Cancel",
    onDismiss: (() -> Unit)? = null,
    icon: ImageVector? = null,
    confirmColor: Color = MaterialTheme.colorScheme.primary,
    confirmEnabled: Boolean = true,
    confirmBusy: Boolean = false,
    dismissEnabled: Boolean = true,
    dismissIcon: ImageVector? = null,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) {
    val haptics = LocalHaptics.current
    AlertDialog(
        onDismissRequest = onDismissRequest,
        properties = properties,
        shape = MaterialTheme.shapes.large,
        containerColor = MaterialTheme.colorScheme.surface,
        icon = icon?.let { { Icon(it, contentDescription = null) } },
        title = { Text(title) },
        text = content,
        confirmButton = {
            if (confirmText != null) {
                TextButton(
                    onClick = {
                        haptics.commit()
                        onConfirm()
                    },
                    enabled = confirmEnabled && !confirmBusy,
                ) {
                    if (confirmBusy) {
                        ListenUpLoadingIndicatorSmall()
                    } else {
                        Text(confirmText, color = if (confirmEnabled) confirmColor else Color.Unspecified)
                    }
                }
            }
        },
        dismissButton =
            if (dismissText != null && onDismiss != null) {
                {
                    TextButton(
                        onClick = {
                            haptics.press()
                            onDismiss()
                        },
                        enabled = dismissEnabled,
                    ) {
                        if (dismissIcon != null) {
                            Icon(
                                imageVector = dismissIcon,
                                contentDescription = null,
                                modifier = Modifier.size(ButtonDefaults.IconSize),
                            )
                            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        }
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
