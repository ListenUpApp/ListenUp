package com.calypsan.listenup.client.features.bookdetail

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.client.design.components.ListenUpDestructiveDialog
import com.calypsan.listenup.client.presentation.error.localized
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_delete_book_blocked
import listenup.composeapp.generated.resources.book_detail_delete_book_body
import listenup.composeapp.generated.resources.book_detail_delete_book_confirm
import listenup.composeapp.generated.resources.book_detail_delete_book_title
import listenup.composeapp.generated.resources.common_cancel
import org.jetbrains.compose.resources.stringResource

/**
 * Confirmation for **deleting a book from the server's disk** — the folder and everything in it.
 *
 * The body names all four things that go: the book, how many files ListenUp tracks there, what they
 * weigh, and — said out loud rather than implied — that everything else in the folder goes with
 * them. The tracked count is deliberately framed as *tracked*: 66 folders in a real library carry
 * bonus PDFs the app never modelled, and a bare "3 files" would be a number the user could check
 * against the folder and find wrong. Understating what a permanent delete removes is the one thing
 * this dialog cannot do.
 *
 * [error] is the typed refusal from a previous attempt, rendered underneath. A
 * [BookError.FolderNotExclusive] names the book that blocked the delete, because "another book
 * shares this folder" without saying which one leaves the admin nothing to act on.
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
    val body = stringResource(Res.string.book_detail_delete_book_body, trackedFileCount, formatFileSize(trackedBytes))
    val refusal =
        when (error) {
            null -> {
                ""
            }

            is BookError.FolderNotExclusive -> {
                "\n\n" + stringResource(Res.string.book_detail_delete_book_blocked, error.otherBookTitle)
            }

            else -> {
                "\n\n" + error.localized()
            }
        }
    ListenUpDestructiveDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.book_detail_delete_book_title, bookTitle),
        text = body + refusal,
        confirmText = stringResource(Res.string.book_detail_delete_book_confirm),
        onConfirm = { if (!isDeleting) onConfirm() },
        dismissText = stringResource(Res.string.common_cancel),
        onDismiss = onDismiss,
        // A warning triangle, not the bin the "delete download" dialog wears: the two are one menu
        // apart and only one of them destroys the user's files.
        icon = Icons.Default.Warning,
    )
}
