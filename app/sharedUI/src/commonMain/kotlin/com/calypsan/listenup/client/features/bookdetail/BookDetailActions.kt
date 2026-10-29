package com.calypsan.listenup.client.features.bookdetail

import androidx.compose.runtime.Stable
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailMenuItem

/**
 * Everything a reader can do from Book Detail, in one stable value both layouts take.
 *
 * What the screen shows travels separately, in `BookDetailUiState.Ready`; this is only the ways out.
 * Built once per screen and remembered, so a recomposition that changes none of its inputs hands
 * both layouts the same instance and they can skip.
 */
@Stable
data class BookDetailActions(
    val onOpenDocument: (docId: String) -> Unit = {},
    val onBackClick: () -> Unit,
    val onEditClick: () -> Unit,
    val onFindMetadataClick: () -> Unit,
    val onEditChaptersClick: () -> Unit,
    val onMarkCompleteClick: () -> Unit,
    val onMarkNotStartedClick: () -> Unit,
    val onRestartClick: () -> Unit,
    val onAddToShelfClick: () -> Unit,
    val onAddToCollectionClick: () -> Unit,
    val onShareClick: () -> Unit,
    val onDeleteBookClick: () -> Unit,
    val onPlayClick: () -> Unit,
    val onRetryConnection: () -> Unit,
    val onDownloadClick: () -> Unit,
    val onCancelClick: () -> Unit,
    val onDeleteClick: () -> Unit,
    val onSeriesClick: (seriesId: String) -> Unit,
    val onContributorClick: (contributorId: String) -> Unit,
    val onGenreClick: (genreId: String) -> Unit,
    val onTagClick: (tagId: String, tagName: String) -> Unit,
    val onMoodClick: (moodId: String, moodName: String) -> Unit,
    val onUserProfileClick: (userId: String) -> Unit,
    val onSeeAllReaders: (bookId: String) -> Unit = {},
    val onFindHardcoverMatch: ((bookId: String) -> Unit)? = null,
    val onReleaseFromInboxClick: () -> Unit = {},
    val onRestoreToAllBooksClick: () -> Unit = {},
    /** Opens a collection from the Visibility card; null (the frozen desktop) shows plain names. */
    val onCollectionClick: ((collectionId: String) -> Unit)? = null,
    /** Plays this book from a chapter's start, in book milliseconds: a seek when it is loaded, a prepare otherwise. */
    val onPlayFromChapter: (startMs: Long) -> Unit = {},
    /** Opens the Files & audio sheet: the Details row, and the scan warning's Review. */
    val onOpenFilesAndAudio: () -> Unit = {},
    /** Opens the Maps & illustrations list, when the book has more than one document. */
    val onOpenDocuments: () -> Unit = {},
)

/** Runs the action behind a More menu [item]. */
internal fun BookDetailActions.perform(item: BookDetailMenuItem) =
    when (item) {
        BookDetailMenuItem.Share -> onShareClick()
        BookDetailMenuItem.MarkFinished -> onMarkCompleteClick()
        BookDetailMenuItem.Restart -> onRestartClick()
        BookDetailMenuItem.MarkNotStarted -> onMarkNotStartedClick()
        BookDetailMenuItem.EditDetails -> onEditClick()
        BookDetailMenuItem.MatchDetails -> onFindMetadataClick()
        BookDetailMenuItem.EditChapters -> onEditChaptersClick()
        BookDetailMenuItem.AddToCollection -> onAddToCollectionClick()
        BookDetailMenuItem.DeleteFromServer -> onDeleteBookClick()
    }
