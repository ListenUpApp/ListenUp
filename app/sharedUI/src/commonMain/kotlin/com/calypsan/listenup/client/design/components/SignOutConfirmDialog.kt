package com.calypsan.listenup.client.design.components

import androidx.compose.runtime.Composable
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_cancel
import listenup.composeapp.generated.resources.common_sign_out
import listenup.composeapp.generated.resources.settings_are_you_sure_you_want
import org.jetbrains.compose.resources.stringResource

/**
 * The one "Sign out?" confirmation, shared by every place that can sign the user out.
 *
 * Signing out drops the session and asks for credentials again, so no surface gets to do it in a
 * single stray tap: Settings and the navigation rail both ask through this dialog, and so read
 * and behave identically.
 *
 * @param onConfirm Signs out. The caller also closes the dialog.
 * @param onDismiss Closes the dialog without signing out.
 */
@Composable
fun SignOutConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ListenUpDestructiveDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.common_sign_out),
        text = stringResource(Res.string.settings_are_you_sure_you_want),
        confirmText = stringResource(Res.string.common_sign_out),
        onConfirm = onConfirm,
        dismissText = stringResource(Res.string.common_cancel),
    )
}
