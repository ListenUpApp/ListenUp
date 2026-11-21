package com.calypsan.listenup.client.features.bookdetail

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.design.components.ListenUpAlertDialog
import com.calypsan.listenup.client.presentation.bookdetail.byteSizeLabel
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.downloads_confirm_cancel_body
import listenup.composeapp.generated.resources.downloads_confirm_cancel_confirm
import listenup.composeapp.generated.resources.downloads_confirm_cancel_keep
import listenup.composeapp.generated.resources.downloads_confirm_cancel_title
import org.jetbrains.compose.resources.stringResource

/**
 * Asks before a cancel that would discard a lot ([com.calypsan.listenup.client.domain.model.cancelNeedsConfirmation]:
 * more than about 100 MB, or a quarter of the book). Smaller cancels happen at once. [savedBytes] is what would go.
 */
@Composable
fun CancelDownloadDialog(
    savedBytes: Long,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ListenUpAlertDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.downloads_confirm_cancel_title),
        text = stringResource(Res.string.downloads_confirm_cancel_body, byteSizeLabel(savedBytes)),
        confirmText = stringResource(Res.string.downloads_confirm_cancel_confirm),
        onConfirm = onConfirm,
        dismissText = stringResource(Res.string.downloads_confirm_cancel_keep),
        onDismiss = onDismiss,
    )
}
