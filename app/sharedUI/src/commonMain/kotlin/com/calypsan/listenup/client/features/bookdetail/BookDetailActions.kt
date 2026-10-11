package com.calypsan.listenup.client.features.bookdetail

import androidx.compose.runtime.Stable

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
)
