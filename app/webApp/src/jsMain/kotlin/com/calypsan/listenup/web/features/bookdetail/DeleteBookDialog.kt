package com.calypsan.listenup.web.features.bookdetail

import com.calypsan.listenup.client.presentation.bookdetail.byteSizeLabel
import androidx.compose.runtime.Composable
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.client.domain.model.BookDocument
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailUiState
import com.calypsan.listenup.web.design.ConfirmDialog

/**
 * Confirmation for **deleting a book from the server** — its folder and everything in it (spec §2.8).
 *
 * The body names the files ListenUp tracks there (audio plus documents) and what they weigh, then says out loud that
 * everything else in its folder goes too, for every member, with no undo. No folder path: the client does not know the
 * book's folder (only its library root, which is NOT deleted), so naming one would be wrong in the worst place.
 *
 * **Delete book** wears the danger colour and waits for "I understand the folder is deleted too" to be ticked, every
 * time the dialog opens: one click is not enough for a delete that reaches every device.
 *
 * ⛔ It stays open when the server refuses. [BookDetailUiState.Ready.deleteError] renders inside it, and a
 * [BookError.FolderNotExclusive] names the book that blocked the delete — "another book shares this folder" without
 * saying which leaves the admin nothing to act on. Success never returns here: `BookDeleted` takes the reader off the
 * page.
 */
@Composable
internal fun DeleteBookDialog(
    ready: BookDetailUiState.Ready,
    documents: List<BookDocument>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val trackedFiles = ready.book.audioFiles.size + documents.size
    val trackedBytes = ready.book.audioFiles.sumOf { it.size } + documents.sumOf { it.size }
    val files = if (trackedFiles == 1) "1 file" else "$trackedFiles files"
    ConfirmDialog(
        open = true,
        title = "Delete “${ready.book.title}” from the server?",
        // The natives' words (`book_detail_delete_server_body`), character for character.
        body =
            "This deletes $files (${byteSizeLabel(trackedBytes)}) and everything else in its folder from your " +
                "server. Every member loses it, and downloads are removed from their devices. This can’t be undone.",
        confirmLabel = "Delete book",
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        error = ready.deleteError?.let(::refusal),
        confirmEnabled = !ready.isDeletingBook,
        acknowledgement = "I understand the folder is deleted too",
        destructive = true,
    )
}

/** What the reader is told when the server said no. */
private fun refusal(error: AppError): String =
    if (error is BookError.FolderNotExclusive) {
        "“${error.otherBookTitle}” is in the same folder, so deleting this book would take it too. " +
            "Nothing was deleted."
    } else {
        error.message
    }
