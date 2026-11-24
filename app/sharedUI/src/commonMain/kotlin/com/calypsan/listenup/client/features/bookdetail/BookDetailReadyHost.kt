package com.calypsan.listenup.client.features.bookdetail

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.design.components.LocalSnackbarHostState
import com.calypsan.listenup.client.domain.model.BookDetail
import com.calypsan.listenup.client.domain.model.BookDocument
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.domain.model.DownloadOutcome
import com.calypsan.listenup.client.domain.model.cancelNeedsConfirmation
import com.calypsan.listenup.client.domain.model.discardableBytes
import com.calypsan.listenup.client.domain.model.knownTotalBytes
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import com.calypsan.listenup.client.features.admin.inbox.ReleaseToEveryoneDialog
import com.calypsan.listenup.client.features.bookdetail.components.DocumentsSheet
import com.calypsan.listenup.client.features.bookdetail.components.FilesAndAudioSheet
import com.calypsan.listenup.client.features.bookdetail.components.MarkCompleteDialog
import com.calypsan.listenup.client.features.bookdetail.components.MarkNotStartedDialog
import com.calypsan.listenup.client.features.bookdetail.components.RestartBookDialog
import com.calypsan.listenup.client.features.library.CollectionPickerSheet
import com.calypsan.listenup.client.features.library.ShelfPickerSheet
import com.calypsan.listenup.client.features.match.LastMatchHost
import com.calypsan.listenup.client.features.share.shareBookLink
import com.calypsan.listenup.client.playback.PlayerSession
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailUiState
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailViewModel
import com.calypsan.listenup.client.presentation.error.localizedString
import com.calypsan.listenup.client.share.bookShareUrl
import com.calypsan.listenup.core.BookId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_insufficient_storage
import listenup.composeapp.generated.resources.book_detail_share_unavailable
import org.jetbrains.compose.resources.getString
import org.koin.compose.koinInject

/**
 * The confirmation Book Detail is asking for, if any. Only one can be open.
 *
 * [RemoveDownload] forgets the downloaded copy and can be undone by downloading again;
 * [DeleteBookFromDisk] deletes the book and its folder from disk and cannot.
 */
private enum class BookDetailDialog {
    RemoveDownload,
    CancelDownload,
    DeleteBookFromDisk,
    MarkComplete,
    MarkNotStarted,
    Restart,
    ReleaseFromInbox,
    FilesAndAudio,
    Documents,
}

/**
 * Ready-state host for [BookDetailScreen]. Holds screen-scoped state
 * (dialogs) and delegates layout to [BookDetailContent].
 *
 * Reachability, download status, and wifi state are owned by [BookDetailViewModel]
 * and read from [state] — no inline observation needed here.
 */
@Suppress("LongParameterList")
@Composable
internal fun BookDetailReadyContent(
    bookId: String,
    state: BookDetailUiState.Ready,
    viewModel: BookDetailViewModel,
    onBackClick: () -> Unit,
    onEditClick: (bookId: String) -> Unit,
    onMatchDetailsClick: (bookId: String) -> Unit,
    onEditChaptersClick: (bookId: String) -> Unit,
    onSeriesClick: (seriesId: String) -> Unit,
    onContributorClick: (contributorId: String) -> Unit,
    onGenreClick: (genreId: String) -> Unit,
    onTagClick: (tagId: String, tagName: String) -> Unit,
    onMoodClick: (moodId: String, moodName: String) -> Unit,
    onUserProfileClick: (userId: String) -> Unit,
    onSeeAllReaders: (bookId: String) -> Unit,
    onFindHardcoverMatch: ((bookId: String) -> Unit)?,
    onCollectionClick: ((collectionId: String) -> Unit)?,
    landOnReaders: Boolean,
) {
    val platformActions: BookDetailPlatformActions = koinInject()
    val instanceRepository: InstanceRepository = koinInject()
    val playerSession: PlayerSession = koinInject()
    val scope = rememberCoroutineScope()
    val snackbarHostState = LocalSnackbarHostState.current
    val documents by viewModel.documents.collectAsStateWithLifecycle()
    // Delayed against a fast prepare (see PlayerSession.preparingBookIdUi) — never flashes on a
    // book that starts playing quickly. Compared against this screen's own bookId so ONLY this
    // book's play button goes busy, not every open Book Detail screen at once.
    val preparingBookId by playerSession.preparingBookIdUi.collectAsStateWithLifecycle(initialValue = null)
    val isPreparing = preparingBookId == BookId(bookId)

    var dialog by remember { mutableStateOf<BookDetailDialog?>(null) }
    // Read by the remembered Cancel action, which outlives a single state.
    val currentDownloadStatus = rememberUpdatedState(state.downloadStatus)

    val actions =
        rememberBookDetailActions(
            bookId = bookId,
            book = state.book,
            viewModel = viewModel,
            scope = scope,
            platformActions = platformActions,
            instanceRepository = instanceRepository,
            snackbarHostState = snackbarHostState,
            currentDownloadStatus = currentDownloadStatus,
            onShowDialog = { dialog = it },
            onBackClick = onBackClick,
            onEditClick = onEditClick,
            onMatchDetailsClick = onMatchDetailsClick,
            onEditChaptersClick = onEditChaptersClick,
            onSeriesClick = onSeriesClick,
            onContributorClick = onContributorClick,
            onGenreClick = onGenreClick,
            onTagClick = onTagClick,
            onMoodClick = onMoodClick,
            onUserProfileClick = onUserProfileClick,
            onSeeAllReaders = onSeeAllReaders,
            onFindHardcoverMatch = onFindHardcoverMatch,
            onCollectionClick = onCollectionClick,
        )

    BookDetailContent(
        bookId = bookId,
        state = state,
        actions = actions,
        documents = documents,
        isPreparing = isPreparing,
        lastMatchRow = { modifier -> LastMatchHost(viewModel = viewModel, modifier = modifier) },
        landOnReaders = landOnReaders,
    )

    BookDetailDialogHost(
        dialog = dialog,
        bookId = bookId,
        state = state,
        documents = documents,
        viewModel = viewModel,
        platformActions = platformActions,
        scope = scope,
        onDismiss = { dialog = null },
    )

    BookDetailPickers(
        state = state,
        viewModel = viewModel,
        snackbarHostState = snackbarHostState,
    )
}

@Suppress("LongParameterList")
@Composable
private fun rememberBookDetailActions(
    bookId: String,
    book: BookDetail,
    viewModel: BookDetailViewModel,
    scope: CoroutineScope,
    platformActions: BookDetailPlatformActions,
    instanceRepository: InstanceRepository,
    snackbarHostState: SnackbarHostState,
    currentDownloadStatus: State<BookDownloadStatus>,
    onShowDialog: (BookDetailDialog) -> Unit,
    onBackClick: () -> Unit,
    onEditClick: (bookId: String) -> Unit,
    onMatchDetailsClick: (bookId: String) -> Unit,
    onEditChaptersClick: (bookId: String) -> Unit,
    onSeriesClick: (seriesId: String) -> Unit,
    onContributorClick: (contributorId: String) -> Unit,
    onGenreClick: (genreId: String) -> Unit,
    onTagClick: (tagId: String, tagName: String) -> Unit,
    onMoodClick: (moodId: String, moodName: String) -> Unit,
    onUserProfileClick: (userId: String) -> Unit,
    onSeeAllReaders: (bookId: String) -> Unit,
    onFindHardcoverMatch: ((bookId: String) -> Unit)?,
    onCollectionClick: ((collectionId: String) -> Unit)?,
): BookDetailActions =
    // One instance for as long as none of what the actions reach for changes, so a recomposition
    // that only moves `state` does not make both layouts recompose over fresh callbacks.
    remember(
        bookId,
        book,
        viewModel,
        scope,
        platformActions,
        instanceRepository,
        snackbarHostState,
        onBackClick,
        onEditClick,
        onMatchDetailsClick,
        onEditChaptersClick,
        onSeriesClick,
        onContributorClick,
        onGenreClick,
        onTagClick,
        onMoodClick,
        onUserProfileClick,
        onSeeAllReaders,
        onFindHardcoverMatch,
        onCollectionClick,
    ) {
        BookDetailActions(
            onOpenDocument = { docId -> viewModel.onOpenDocument(docId) },
            onBackClick = onBackClick,
            onEditClick = { onEditClick(bookId) },
            onFindMetadataClick = { onMatchDetailsClick(bookId) },
            onEditChaptersClick = { onEditChaptersClick(bookId) },
            onMarkCompleteClick = { onShowDialog(BookDetailDialog.MarkComplete) },
            onMarkNotStartedClick = { onShowDialog(BookDetailDialog.MarkNotStarted) },
            onRestartClick = { onShowDialog(BookDetailDialog.Restart) },
            onAddToShelfClick = { viewModel.showShelfPicker() },
            onAddToCollectionClick = { viewModel.showCollectionPicker() },
            onShareClick = {
                scope.launch {
                    // The RPC-backed server identity, through the one builder every client shares.
                    shareBookLink(
                        url = bookShareUrl(instanceRepository, book.id),
                        title = book.title,
                        share = platformActions::shareText,
                        onNoLink = {
                            snackbarHostState.showSnackbar(getString(Res.string.book_detail_share_unavailable))
                        },
                    )
                }
            },
            onDeleteBookClick = {
                viewModel.clearDeleteError()
                onShowDialog(BookDetailDialog.DeleteBookFromDisk)
            },
            onPlayClick = { platformActions.playBook(BookId(bookId)) },
            onRetryConnection = { viewModel.retryConnection() },
            onUserProfileClick = onUserProfileClick,
            onDownloadClick = {
                scope.launch {
                    val result = platformActions.downloadBook(BookId(bookId))
                    handleDownloadResult(result) { message -> snackbarHostState.showSnackbar(message) }
                }
            },
            onPauseClick = {
                scope.launch {
                    platformActions.pauseDownload(BookId(bookId))
                }
            },
            onCancelClick = {
                // Asks first only when the cancel would discard a lot (canvas decision 4).
                val status = currentDownloadStatus.value
                if (cancelNeedsConfirmation(status.discardableBytes(), status.knownTotalBytes())) {
                    onShowDialog(BookDetailDialog.CancelDownload)
                } else {
                    scope.launch { platformActions.cancelDownload(BookId(bookId)) }
                }
            },
            onDeleteClick = { onShowDialog(BookDetailDialog.RemoveDownload) },
            onOpenFilesAndAudio = { onShowDialog(BookDetailDialog.FilesAndAudio) },
            onOpenDocuments = { onShowDialog(BookDetailDialog.Documents) },
            onSeriesClick = onSeriesClick,
            onContributorClick = onContributorClick,
            onGenreClick = onGenreClick,
            onTagClick = onTagClick,
            onMoodClick = onMoodClick,
            onSeeAllReaders = onSeeAllReaders,
            onFindHardcoverMatch = onFindHardcoverMatch,
            onReleaseFromInboxClick = { onShowDialog(BookDetailDialog.ReleaseFromInbox) },
            onRestoreToAllBooksClick = { viewModel.restoreToAllBooks() },
            onCollectionClick = onCollectionClick,
            onPlayFromChapter = { startMs -> platformActions.playFromChapter(BookId(bookId), startMs) },
        )
    }

@Suppress("LongMethod")
@Composable
private fun BookDetailDialogHost(
    dialog: BookDetailDialog?,
    bookId: String,
    state: BookDetailUiState.Ready,
    documents: List<BookDocument>,
    viewModel: BookDetailViewModel,
    platformActions: BookDetailPlatformActions,
    scope: CoroutineScope,
    onDismiss: () -> Unit,
) {
    val book = state.book
    when (dialog) {
        BookDetailDialog.CancelDownload -> {
            CancelDownloadDialog(
                savedBytes = state.downloadStatus.discardableBytes(),
                onConfirm = {
                    scope.launch { platformActions.cancelDownload(BookId(bookId)) }
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        }

        BookDetailDialog.RemoveDownload -> {
            DeleteDownloadDialog(
                downloadSize = state.downloadStatus.downloadedOrTotalBytes(),
                onConfirm = {
                    scope.launch {
                        platformActions.deleteDownload(BookId(bookId))
                    }
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        }

        BookDetailDialog.DeleteBookFromDisk -> {
            // What ListenUp knows is in the folder: the audio files plus any synced documents. The
            // dialog frames this as "tracked", because the folder may hold more (66 folders in a real
            // library carry bonus PDFs) and every one of those goes too.
            val trackedFiles = book.audioFiles.size + documents.size
            val trackedBytes = book.audioFiles.sumOf { it.size } + documents.sumOf { it.size }
            DeleteBookDialog(
                bookTitle = book.title,
                trackedFileCount = trackedFiles,
                trackedBytes = trackedBytes,
                error = state.deleteError,
                isDeleting = state.isDeletingBook,
                onConfirm = { viewModel.deleteBook() },
                // Left open on failure so the refusal is readable; the success path never returns here,
                // it navigates away on BookDetailNavAction.BookDeleted.
                onDismiss = {
                    onDismiss()
                    viewModel.clearDeleteError()
                },
            )
        }

        BookDetailDialog.MarkNotStarted -> {
            MarkNotStartedDialog(
                onConfirm = {
                    viewModel.discardProgress()
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        }

        BookDetailDialog.Restart -> {
            RestartBookDialog(
                onConfirm = {
                    viewModel.restartBook()
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        }

        BookDetailDialog.MarkComplete -> {
            MarkCompleteDialog(
                startedAtMs = state.startedAtMs,
                onConfirm = { startedAt, finishedAt ->
                    viewModel.markComplete(startedAt = startedAt, finishedAt = finishedAt)
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        }

        // Release asks first. Confirming closes the question at once; the held section's
        // Release button carries the in-flight state, and success turns this into the ordinary page.
        BookDetailDialog.ReleaseFromInbox -> {
            if (state.isHeld) {
                ReleaseToEveryoneDialog(
                    bookCount = 1,
                    onConfirm = {
                        onDismiss()
                        viewModel.releaseFromInbox()
                    },
                    onDismiss = onDismiss,
                )
            }
        }

        BookDetailDialog.FilesAndAudio -> {
            FilesAndAudioSheet(audioFiles = book.audioFiles, onDismiss = onDismiss)
        }

        BookDetailDialog.Documents -> {
            DocumentsSheet(
                documents = documents,
                onOpen = { docId ->
                    viewModel.onOpenDocument(docId)
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        }

        null -> {
            // No question is open.
        }
    }
}

@Composable
private fun BookDetailPickers(
    state: BookDetailUiState.Ready,
    viewModel: BookDetailViewModel,
    snackbarHostState: SnackbarHostState,
) {
    if (state.showShelfPicker) {
        val myShelves by viewModel.myShelves.collectAsStateWithLifecycle()
        val shelvesContainingBook by viewModel.shelvesContainingBook.collectAsStateWithLifecycle()

        ShelfPickerSheet(
            shelves = myShelves,
            selectedBookCount = 1,
            onShelfSelected = { shelfId -> viewModel.addBookToShelf(shelfId) },
            onCreateAndAddToShelf = { name -> viewModel.createShelfAndAddBook(name) },
            onDismiss = { viewModel.hideShelfPicker() },
            isLoading = state.isAddingToShelf,
            shelvesContainingBook = shelvesContainingBook,
        )
    }

    // Collections are admin-managed — gate the picker presentation on isAdmin as
    // defense-in-depth, so it can't render for a non-admin even if showCollectionPicker leaks true.
    if (state.showCollectionPicker && state.isAdmin) {
        val collections by viewModel.collections.collectAsStateWithLifecycle()

        CollectionPickerSheet(
            collections = collections,
            selectedBookCount = 1,
            onCollectionSelected = { collectionId -> viewModel.addBookToCollection(collectionId) },
            onCreateAndAddToCollection = { name -> viewModel.createCollectionAndAddBook(name) },
            onDismiss = { viewModel.hideCollectionPicker() },
            isLoading = state.isAddingToCollection,
            canCreate = state.isAdmin,
        )
    }

    state.shelfError?.let { error ->
        LaunchedEffect(error) {
            snackbarHostState.showSnackbar(error)
            viewModel.clearShelfError()
        }
    }

    state.collectionError?.let { error ->
        LaunchedEffect(error) {
            snackbarHostState.showSnackbar(error)
            viewModel.clearCollectionError()
        }
    }
}

/** Returns the best available byte count to display in the delete dialog. */
private fun BookDownloadStatus.downloadedOrTotalBytes(): Long =
    when (this) {
        is BookDownloadStatus.Completed -> totalBytes
        is BookDownloadStatus.InProgress -> downloadedBytes
        is BookDownloadStatus.Paused -> downloadedBytes
        is BookDownloadStatus.NotDownloaded, is BookDownloadStatus.Failed -> 0L
    }

private suspend fun handleDownloadResult(
    result: AppResult<DownloadOutcome>,
    showSnackbar: suspend (String) -> Unit,
) {
    if (result is AppResult.Failure) {
        showSnackbar(result.error.localizedString())
        return
    }
    val outcome = (result as AppResult.Success).data
    if (outcome is DownloadOutcome.InsufficientStorage) {
        val requiredMb = (outcome.requiredBytes / 1_000_000).toInt()
        val availableMb = (outcome.availableBytes / 1_000_000).toInt()
        showSnackbar(getString(Res.string.book_detail_insufficient_storage, requiredMb, availableMb))
    }
    // DownloadOutcome.Started and DownloadOutcome.AlreadyDownloaded: no UI action needed
}
