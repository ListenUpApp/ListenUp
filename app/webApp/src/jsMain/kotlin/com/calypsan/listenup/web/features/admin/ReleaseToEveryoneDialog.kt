package com.calypsan.listenup.web.features.admin

import androidx.compose.runtime.Composable
import com.calypsan.listenup.web.design.ConfirmDialog

/**
 * "Release to everyone?" — the one question asked before a held book goes out, from Book Detail
 * (one book) and from the inbox page (the selection). Spec §7's copy, in place of the old "These
 * books will become visible to everyone on this server…". Cancel comes first and takes focus
 * ([ConfirmDialog]'s own arrangement); the verb is Release.
 */
@Composable
fun ReleaseToEveryoneDialog(
    open: Boolean,
    bookCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ConfirmDialog(
        open = open,
        title = "Release to everyone?",
        body = releaseToEveryoneBody(bookCount),
        confirmLabel = "Release",
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/** "…find and play it." for one book, "…them." for several. */
internal fun releaseToEveryoneBody(bookCount: Int): String =
    if (bookCount == 1) {
        "Every member will be able to find and play it."
    } else {
        "Every member will be able to find and play them."
    }
