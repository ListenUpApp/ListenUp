package com.calypsan.listenup.client.features.admin.inbox

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.design.components.ListenUpAlertDialog
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.admin_release
import listenup.composeapp.generated.resources.admin_release_to_everyone
import listenup.composeapp.generated.resources.admin_release_to_everyone_body
import listenup.composeapp.generated.resources.admin_release_to_everyone_body_plural
import listenup.composeapp.generated.resources.common_cancel
import org.jetbrains.compose.resources.stringResource

/**
 * "Release to everyone?" — the one question asked before a held book goes out, from Book Detail
 * (one book) and from the inbox page (the selection). Spec §7's copy, in place of the old "These
 * books will become visible to ALL users."
 *
 * An M3 alert dialog with a primary (coral) Release, not the destructive red one: nothing is
 * deleted, the book is shared. It can't be simply undone, which is why it asks at all.
 *
 * @param bookCount how many books the release covers; picks "it" or "them"
 */
@Composable
fun ReleaseToEveryoneDialog(
    bookCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ListenUpAlertDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.admin_release_to_everyone),
        text =
            if (bookCount == 1) {
                stringResource(Res.string.admin_release_to_everyone_body)
            } else {
                stringResource(Res.string.admin_release_to_everyone_body_plural)
            },
        confirmText = stringResource(Res.string.admin_release),
        onConfirm = onConfirm,
        dismissText = stringResource(Res.string.common_cancel),
        onDismiss = onDismiss,
        // The inbox glyph the held section and the Library entry wear (canvas `A-Detail-Confirm`).
        icon = Icons.Outlined.Inbox,
    )
}
