package com.calypsan.listenup.client.presentation.bookdetail

import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.client.domain.model.BookDetail
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.domain.model.BookVisibility
import com.calypsan.listenup.client.domain.model.Genre
import com.calypsan.listenup.client.domain.model.Mood
import com.calypsan.listenup.client.domain.model.Tag
import com.calypsan.listenup.client.domain.repository.DocumentRepository
import com.calypsan.listenup.client.domain.repository.PermissionsRepository

/**
 * UI state for the Book Detail screen.
 *
 * Sealed hierarchy — [Ready] carries all book-dependent fields. Its transient fields (open pickers, writes in
 * flight, inline refusals) come from the ViewModel's private per-book overlay, laid over what Room says, so a Room
 * re-emission never closes a picker and a book switch never carries one over.
 */
sealed interface BookDetailUiState {
    /** Pre-load placeholder or in-flight transition between books. */
    data object Loading : BookDetailUiState

    /** Book loaded successfully. */
    data class Ready(
        val book: BookDetail,
        val isAdmin: Boolean = false,
        /**
         * The signed-in user may edit this book's metadata (Edit Book, Find Metadata, the chapter editor).
         * From [PermissionsRepository]; admins always may. `isAdmin` stays for the admin-only actions
         * (collections, delete).
         */
        val canEditMetadata: Boolean = false,
        val isComplete: Boolean = false,
        val startedAtMs: Long? = null,
        val isMarkingComplete: Boolean = false,
        val isDiscardingProgress: Boolean = false,
        val isRestarting: Boolean = false,
        val subtitle: String? = null,
        /**
         * The book's series as paths — one line per series, "Cosmere › Mistborn #1" — with every
         * part a link. A membership already implied by a deeper one is left out.
         */
        val seriesPaths: List<BookSeriesPath> = emptyList(),
        /**
         * Book synopsis for the Description section. Named `descriptionText` (not `description`)
         * deliberately: a Kotlin property called `description` is shadowed on the Swift/SKIE side by
         * the universal `CustomStringConvertible.description`, so `ready.description` there returns
         * the object's `toString()` instead of this field. The distinct name keeps the iOS accessor
         * unambiguous.
         */
        val descriptionText: String = "",
        val narrators: String = "",
        val year: Int? = null,
        val addedAt: Long? = null,
        val hasScanWarning: Boolean = false,
        val chapters: List<ChapterUiModel> = emptyList(),
        val progress: Float? = null,
        val timeRemainingFormatted: String? = null,
        val genres: List<Genre> = emptyList(),
        val tags: List<Tag> = emptyList(),
        val allTags: List<Tag> = emptyList(),
        val moods: List<Mood> = emptyList(),
        val isLoadingTags: Boolean = false,
        val showShelfPicker: Boolean = false,
        val isAddingToShelf: Boolean = false,
        val shelfError: String? = null,
        val showCollectionPicker: Boolean = false,
        val isAddingToCollection: Boolean = false,
        val collectionError: String? = null,
        /** True while [BookDetailViewModel.deleteBook] is in flight — the confirm dialog goes busy. */
        val isDeletingBook: Boolean = false,
        /**
         * The typed refusal from the last delete attempt, or null. Held as an [AppError] rather
         * than a rendered string so the UI can name the blocking book from
         * [com.calypsan.listenup.api.error.BookError.FolderNotExclusive] instead of parsing a
         * sentence back apart.
         */
        val deleteError: AppError? = null,
        /**
         * Held for review in the admin inbox — hidden from every member until released. A held book
         * is **triage-only** (spec §8): when true, every platform renders the triage layout — the
         * held section ("Held for review · Hidden from all members") with **Edit** and **Release**
         * — and no other action. [canPlay], [canDownload] and [showServerWarning] are already forced
         * false for it. Follows Room live; always false on a member's device.
         */
        val isHeld: Boolean = false,
        /** True while [BookDetailViewModel.releaseFromInbox] is in flight — the Release button goes busy. */
        val isReleasingFromInbox: Boolean = false,
        /**
         * Who cannot see this book — an admin's view only; null on a member's device, which cannot
         * know. Live: a share added or revoked updates it with no refresh. The visibility section
         * renders only [BookVisibility.Restricted] and [BookVisibility.Stranded]; for a held book
         * ([isHeld], [BookVisibility.Held]) the inbox's held section already says "Hidden from all
         * members", so the visibility section stays out of the triage layout.
         */
        val visibility: BookVisibility? = null,
        /**
         * True from [BookDetailViewModel.restoreToAllBooks] until the book stops being
         * [BookVisibility.Stranded]. Usually that is at once, because the local write puts the book
         * in All Books. When the book's library's All Books has not synced, the local write cannot,
         * so this stays true until the server's echo does. That is the honest thing to show.
         */
        val isRestoringToAllBooks: Boolean = false,
        val downloadStatus: BookDownloadStatus = BookDownloadStatus.NotDownloaded(""), // overwritten before emit; "" id never observed
        val isPlaybackAvailable: Boolean = true,
        val canPlay: Boolean = true,
        val canDownload: Boolean = false,
        val showServerWarning: Boolean = false,
        val isWaitingForWifi: Boolean = false,
    ) : BookDetailUiState

    /** Load failure (e.g. the book is not in the local library); carries the typed [error] to localize. */
    data class Error(
        val error: AppError,
    ) : BookDetailUiState
}

/**
 * Per-chapter row data for the book detail screen's chapter list. Pre-formatted
 * for direct display so the Composable layer does no formatting work.
 *
 * [isCurrent] marks the chapter the saved playback position currently sits in,
 * derived in the ViewModel from the position and each chapter's start time. It
 * drives the current-chapter highlight; it is `false` for every chapter when the
 * book has no meaningful progress.
 */
data class ChapterUiModel(
    val id: String,
    val title: String,
    val duration: String,
    val imageUrl: String?,
    val isCurrent: Boolean = false,
    /**
     * Where the chapter starts, in milliseconds. Carried alongside the formatted [duration]
     * because a list is not the only way to draw chapters: the web Book Detail lays them out
     * proportionally along the book, which needs the offsets themselves rather than text. The
     * mobile screens render [duration] and ignore these.
     */
    val startMs: Long = 0L,
    /** How long the chapter runs, in milliseconds. See [startMs]. */
    val durationMs: Long = 0L,
)

/**
 * One-shot navigation and side-effect events emitted by [BookDetailViewModel].
 *
 * Consumed once at the screen entry point via [BookDetailViewModel.navActions].
 */
sealed interface BookDetailNavAction {
    /**
     * Open the in-app document viewer for the given local file.
     *
     * @param localPath Absolute path to the cached document file on disk, as returned
     *   by [DocumentRepository.ensureLocal].
     */
    data class OpenDocumentViewer(val localPath: String) : BookDetailNavAction

    /**
     * Show a transient snackbar informing the user that a viewer is not yet available
     * for this document format.
     */
    data object ShowViewerComingSoon : BookDetailNavAction

    /**
     * The book was deleted from the server, folder and all — leave this screen.
     *
     * A one-shot event rather than a state flag because the row is about to disappear from Room
     * when the tombstone syncs, and a detail screen watching a book that no longer exists has
     * nothing honest left to render.
     */
    data object BookDeleted : BookDetailNavAction
}
