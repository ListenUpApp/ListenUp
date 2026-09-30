package com.calypsan.listenup.web.features.bookdetail

import androidx.compose.runtime.Composable
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.client.domain.model.BookDocument
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailUiState
import com.calypsan.listenup.web.design.ConfirmDialog

/**
 * Confirmation for **deleting a book from the server's disk** — its folder and everything in it.
 *
 * The copy is Android's `DeleteBookDialog`, string for string (`book_detail_delete_book_*`), and for
 * the same reasons: the body names the folder rather than "the library", counts the files ListenUp
 * *tracks* there (audio plus documents — 66 folders in a real library carry bonus PDFs the app never
 * modelled, so a bare "5 files" could be checked against the folder and found wrong), and says that
 * every device loses the book. Understating what a permanent delete removes is the one thing this
 * dialog cannot do.
 *
 * ⛔ It stays open when the server refuses. [BookDetailUiState.Ready.deleteError] renders inside it,
 * and a [BookError.FolderNotExclusive] names the book that blocked the delete — "another book shares
 * this folder" without saying which leaves the admin nothing to act on. Success never returns here:
 * `BookDeleted` takes the reader off the page.
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
    ConfirmDialog(
        open = true,
        title = "Delete “${ready.book.title}”?",
        body =
            "This permanently deletes the book’s folder from your server. ListenUp tracks $trackedFiles files " +
                "in it (${formatBytes(trackedBytes)}), and everything else in that folder goes too — PDFs, " +
                "artwork, bonus material — whether ListenUp shows it or not. The book disappears from every " +
                "device, and this can’t be undone.",
        confirmLabel = "Delete forever",
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        error = ready.deleteError?.let(::refusal),
        confirmEnabled = !ready.isDeletingBook,
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
