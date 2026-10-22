package com.calypsan.listenup.client.features.bookdetail

import com.calypsan.listenup.client.presentation.bookdetail.byteSizeLabel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.runtime.Composable
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.client.design.components.ListenUpDestructiveDialog
import com.calypsan.listenup.client.presentation.error.localized
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_delete_book_blocked
import listenup.composeapp.generated.resources.book_detail_delete_server_body
import listenup.composeapp.generated.resources.book_detail_delete_server_body_one
import listenup.composeapp.generated.resources.book_detail_delete_server_confirm
import listenup.composeapp.generated.resources.book_detail_delete_server_title
import listenup.composeapp.generated.resources.common_cancel
import org.jetbrains.compose.resources.stringResource

/**
 * Confirmation for **deleting a book from the server** — the folder and everything in it (spec §2.8).
 *
 * The body names everything that goes: how many files ListenUp tracks there, what they weigh, the book, and — said
 * out loud rather than implied — everything else in its folder; then who loses it (every member, and their devices'
 * downloads) and that it cannot be undone. The tracked count stays framed as what ListenUp knows: 66 folders in a real
 * library carry bonus PDFs the app never modelled, which is why "everything else in its folder" is in the sentence.
 *
 * [error] is the typed refusal from a previous attempt, rendered underneath. A [BookError.FolderNotExclusive] names the
 * book that blocked the delete, because "another book shares this folder" without saying which one leaves the admin
 * nothing to act on. Focus opens on Cancel (spec §5.2). The confirm is the error-coloured text button every other
 * destructive confirm uses (m3.material.io/components/dialogs/guidelines: dialog actions are text buttons).
 *
 * @param trackedFileCount audio files plus documents ListenUp knows about in this book's folder.
 * @param trackedBytes their combined size.
 * @param isDeleting true while the call is in flight — swallows a second tap on Delete.
 */
@Composable
fun DeleteBookDialog(
    bookTitle: String,
    trackedFileCount: Int,
    trackedBytes: Long,
    error: AppError?,
    isDeleting: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val size = byteSizeLabel(trackedBytes)
    val body =
        if (trackedFileCount == 1) {
            stringResource(Res.string.book_detail_delete_server_body_one, size)
        } else {
            stringResource(Res.string.book_detail_delete_server_body, trackedFileCount, size)
        }
    val refusal =
        when (error) {
            null -> {
                ""
            }

            is BookError.FolderNotExclusive -> {
                "\n\n" + stringResource(Res.string.book_detail_delete_book_blocked, error.otherBookTitle)
            }

            is AppError -> {
                "\n\n" + error.localized()
            }
        }
    ListenUpDestructiveDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.book_detail_delete_server_title, bookTitle),
        text = body + refusal,
        confirmText = stringResource(Res.string.book_detail_delete_server_confirm),
        onConfirm = { if (!isDeleting) onConfirm() },
        dismissText = stringResource(Res.string.common_cancel),
        onDismiss = onDismiss,
        // The board's trash: the remove-download confirm wears no icon now, so the two no longer need telling apart
        // by glyph, and this is the one that deletes files.
        icon = Icons.Outlined.Delete,
        focusDismissFirst = true,
    )
}
