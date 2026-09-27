package com.calypsan.listenup.client.features.admin.upload

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.calypsan.listenup.client.design.components.ListenUpDestructiveDialog
import com.calypsan.listenup.client.design.util.PlatformBackHandler
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.admin_upload_books_keep_uploading
import listenup.composeapp.generated.resources.admin_upload_books_stop_body
import listenup.composeapp.generated.resources.admin_upload_books_stop_confirm
import listenup.composeapp.generated.resources.admin_upload_books_stop_title
import org.jetbrains.compose.resources.stringResource

/**
 * Back during an upload asks before it throws the upload away.
 *
 * Leaving the screen pops the entry, clears the ViewModel and abandons the session, so every staged
 * byte goes with it. The back gesture fires from an edge touch as easily as from intent, so forty
 * minutes in a reflexive swipe must not do that silently — but swallowing Back outright traps the
 * user. The middle path is to ask: "Stop upload?" stops and leaves; dismissing keeps uploading.
 *
 * The question is hoisted ([confirming]) so the toolbar's back arrow can ask it too.
 *
 * @param uploading Whether bytes are in flight. The guard is inert otherwise, and a question left
 *   open when the upload ends on its own is withdrawn — there is nothing left to stop.
 * @param confirming Whether the question is showing.
 * @param onConfirmingChange Shows or withdraws the question.
 * @param onStop Stops the upload and leaves the screen.
 */
@Composable
internal fun StopUploadGuard(
    uploading: Boolean,
    confirming: Boolean,
    onConfirmingChange: (Boolean) -> Unit,
    onStop: () -> Unit,
) {
    PlatformBackHandler(enabled = uploading) { onConfirmingChange(true) }

    LaunchedEffect(uploading) {
        if (!uploading) onConfirmingChange(false)
    }

    if (uploading && confirming) {
        ListenUpDestructiveDialog(
            onDismissRequest = { onConfirmingChange(false) },
            title = stringResource(Res.string.admin_upload_books_stop_title),
            text = stringResource(Res.string.admin_upload_books_stop_body),
            confirmText = stringResource(Res.string.admin_upload_books_stop_confirm),
            onConfirm = {
                onConfirmingChange(false)
                onStop()
            },
            dismissText = stringResource(Res.string.admin_upload_books_keep_uploading),
        )
    }
}
