package com.calypsan.listenup.client.features.bookdetail.components

import com.calypsan.listenup.client.design.components.ListenUpDestructiveDialog
import androidx.compose.runtime.Composable
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_mark_as_not_started
import listenup.composeapp.generated.resources.book_detail_mark_not_started_prompt
import listenup.composeapp.generated.resources.common_cancel
import org.jetbrains.compose.resources.stringResource

/**
 * Confirmation for "Mark as Not Started" — the action clears the book's listening progress.
 *
 * It is destructive and unrecoverable: the position is gone, so a mis-tap costs the user their
 * place in the book. The overflow item used to call straight through to `discardProgress()`, so a
 * single tap on a menu row two pixels from "Add to Shelf" wiped it. iOS already confirms this
 * action; this brings Android to the same bar.
 */
@Composable
fun MarkNotStartedDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ListenUpDestructiveDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.book_detail_mark_as_not_started),
        text = stringResource(Res.string.book_detail_mark_not_started_prompt),
        confirmText = stringResource(Res.string.book_detail_mark_as_not_started),
        onConfirm = onConfirm,
        dismissText = stringResource(Res.string.common_cancel),
    )
}
