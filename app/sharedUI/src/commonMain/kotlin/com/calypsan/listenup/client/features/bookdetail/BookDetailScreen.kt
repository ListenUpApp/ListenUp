package com.calypsan.listenup.client.features.bookdetail

import androidx.compose.ui.semantics.role
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.draw.clip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.design.components.LocalNowPlayingInsets
import com.calypsan.listenup.client.features.match.LastMatchHost
import com.calypsan.listenup.client.features.match.MatchReceiptHost
import com.calypsan.listenup.client.presentation.bookdetail.LastMatchEvent
import listenup.composeapp.generated.resources.match_undo_expired
import listenup.composeapp.generated.resources.match_undone
import com.calypsan.listenup.client.design.components.LocalSnackbarHostState
import com.calypsan.listenup.client.design.components.BookCoverImage
import com.calypsan.listenup.client.design.transitions.bookCoverHeroKey
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.DownloadOutcome
import com.calypsan.listenup.client.features.library.CollectionPickerSheet
import com.calypsan.listenup.client.features.library.ShelfPickerSheet
import com.calypsan.listenup.client.features.bookdetail.components.BookDetailTopBar
import com.calypsan.listenup.client.features.admin.inbox.ReleaseToEveryoneDialog
import com.calypsan.listenup.client.features.bookdetail.components.BookVisibilitySection
import com.calypsan.listenup.client.features.bookdetail.components.HeldForReviewSection
import com.calypsan.listenup.client.features.bookdetail.components.BookDetailSectionContent
import com.calypsan.listenup.client.features.bookdetail.components.DescriptionExpansion
import com.calypsan.listenup.client.features.bookdetail.components.ReadersLanding
import com.calypsan.listenup.client.features.bookdetail.components.HeroSocialLine
import com.calypsan.listenup.client.features.bookdetail.components.PageReaders
import com.calypsan.listenup.client.features.bookdetail.components.rememberPageReaders
import com.calypsan.listenup.client.features.bookdetail.components.shownSections
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailSection
import com.calypsan.listenup.client.features.contributors.CastRole
import com.calypsan.listenup.client.features.bookdetail.components.BookDetailHeroBand
import com.calypsan.listenup.client.features.contributors.FullCastSheetFor
import com.calypsan.listenup.client.features.bookdetail.components.MarkCompleteDialog
import com.calypsan.listenup.client.features.bookdetail.components.MarkNotStartedDialog
import com.calypsan.listenup.client.features.bookdetail.components.OfflineReason
import com.calypsan.listenup.client.features.bookdetail.components.ScanWarningBanner
import com.calypsan.listenup.client.features.bookdetail.components.BookDetailErrorState
import com.calypsan.listenup.client.presentation.bookdetail.PrimaryAction
import com.calypsan.listenup.client.features.bookdetail.components.ListenActionGroup
import com.calypsan.listenup.client.features.bookdetail.components.PhoneHeroCoverSize
import com.calypsan.listenup.client.features.bookdetail.components.RestartBookDialog
import com.calypsan.listenup.client.features.bookdetail.components.ResumeUnit
import com.calypsan.listenup.client.features.bookdetail.components.WideBookDetail
import com.calypsan.listenup.client.features.bookdetail.components.FilesAndAudioSheet
import com.calypsan.listenup.client.features.bookdetail.components.DocumentsSheet
import com.calypsan.listenup.client.domain.model.BookDocument
import com.calypsan.listenup.client.playback.PlayerSession
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailNavAction
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailUiState
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailViewModel
import com.calypsan.listenup.client.presentation.bookdetail.bookDetailMoreMenu
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import com.calypsan.listenup.client.presentation.error.localizedString
import com.calypsan.listenup.client.share.bookShareUrl
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_document_viewer_coming_soon
import listenup.composeapp.generated.resources.book_detail_insufficient_storage
import com.calypsan.listenup.client.design.LocalDeviceContext

/**
 * Immersive book detail screen following Material 3 Expressive Design.
 *
 * Design Philosophy: "Identity -> Talent -> Action -> Story -> Details"
 * Uses Palette API to extract colors from cover art for dynamic theming.
 *
 * Layout Hierarchy (Strict Order):
 * 1. Hero Section - Identity (Cover, Title, Subtitle) with color-extracted gradient
 * 2. The Talent - Who made this (Authors, Narrators - clickable)
 * 3. Primary Actions - What can I do (Play, Download)
 * 4. Context Metadata - Series, Stats, Genres
 * 5. The Hook - What's it about (Description)
 * 6. Tags - User categorization
 * 7. Chapters - Deep dive content
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailScreen(
    bookId: String,
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
    /** Opens another book: a cover on the series shelf. */
    onBookClick: (bookId: String) -> Unit,
    onSeeAllReaders: (bookId: String) -> Unit = {},
    onFindHardcoverMatch: ((bookId: String) -> Unit)? = null,
    /** Opens an admin collection from the Visibility card; null shows the names as plain labels. */
    onCollectionClick: ((collectionId: String) -> Unit)? = null,
    /** Opens the in-app reader on a downloaded document, titled with this book's name. */
    onOpenDocumentViewer: (localPath: String, bookTitle: String) -> Unit = { _, _ -> },
    /** Open with the Readers section in view, once: Home's finishers line (spec §6.4). */
    landOnReaders: Boolean = false,
    /** The receipt after Match details applied; a slot so a test can render the screen without its ViewModel. */
    matchReceipt: @Composable (
        Modifier,
    ) -> Unit = { modifier -> MatchReceiptHost(subjectId = bookId, modifier = modifier) },
    viewModel: BookDetailViewModel = koinViewModel(),
) {
    LaunchedEffect(bookId) {
        viewModel.loadBook(bookId)
    }

    val snackbarHostState = LocalSnackbarHostState.current
    val viewerComingSoonLabel = stringResource(Res.string.book_detail_document_viewer_coming_soon)
    val navPlatformActions: BookDetailPlatformActions = koinInject()

    // Tells the handoff layer which book is on screen, so Android's Continue On can offer it to
    // another device. A DisposableEffect rather than a LaunchedEffect because the *departure*
    // matters as much as the arrival — and it clears by id, so the outgoing screen (disposed after
    // the incoming one appears) cannot wipe a newer book's claim.
    DisposableEffect(bookId) {
        val id = BookId(bookId)
        navPlatformActions.onBookScreenShown(id)
        onDispose { navPlatformActions.onBookScreenHidden(id) }
    }

    // Consume one-shot navigation events from the ViewModel.
    LaunchedEffect(viewModel) {
        viewModel.navActions.collect { action ->
            when (action) {
                is BookDetailNavAction.OpenDocumentViewer -> {
                    // The reader is titled with this book's name, as iOS's is.
                    val title = (viewModel.state.value as? BookDetailUiState.Ready)?.run { book.title }.orEmpty()
                    onOpenDocumentViewer(action.localPath, title)
                }

                is BookDetailNavAction.ShowViewerComingSoon -> {
                    snackbarHostState.showSnackbar(viewerComingSoonLabel)
                }

                is BookDetailNavAction.BookDeleted -> {
                    // Purge this device's copy before leaving: the book's files are gone on the
                    // server, so a download left behind would keep playing a book that no longer
                    // exists — offline, indefinitely, with no way to reach it from the library.
                    navPlatformActions.deleteDownload(BookId(bookId))
                    // Then leave, before the tombstone lands and the row disappears underneath us.
                    onBackClick()
                }
            }
        }
    }

    // "Undo last match" says how it ended as the receipt does: undone, or too late.
    LaunchedEffect(viewModel) {
        viewModel.lastMatchEvents.collect { event ->
            val message =
                when (event) {
                    LastMatchEvent.Undone -> getString(Res.string.match_undone)
                    LastMatchEvent.Expired -> getString(Res.string.match_undo_expired)
                }
            snackbarHostState.showSnackbar(message)
        }
    }

    val state by viewModel.state.collectAsStateWithLifecycle()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        when (val s = state) {
            is BookDetailUiState.Loading -> {
                BookDetailLoadingFrame(bookId = bookId, onBackClick = onBackClick)
            }

            is BookDetailUiState.Error -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    BookDetailTopBar(
                        onBackClick = onBackClick,
                        menu = emptyList(),
                        progressBusy = false,
                        onMenuItem = {},
                        isHeld = false,
                    )
                    // Retry asks the server again (a reconnect runs catch-up); the page turns Ready by itself when
                    // the book lands in Room.
                    BookDetailErrorState(
                        error = s.error,
                        onRetry = { viewModel.retryConnection() },
                        onBack = onBackClick,
                    )
                }
            }

            is BookDetailUiState.Ready -> {
                Box(modifier = Modifier.fillMaxSize()) {
                    BookDetailReadyContent(
                        bookId = bookId,
                        state = s,
                        viewModel = viewModel,
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
                        onBookClick = onBookClick,
                        onSeeAllReaders = onSeeAllReaders,
                        onFindHardcoverMatch = onFindHardcoverMatch,
                        onCollectionClick = onCollectionClick,
                        landOnReaders = landOnReaders,
                    )
                    // The receipt after Match details applied, above the mini player.
                    matchReceipt(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .windowInsetsPadding(LocalNowPlayingInsets.current.union(WindowInsets.navigationBars)),
                    )
                }
            }
        }
    }
}

/**
 * The detail screen's first frame, before the book has loaded: the top bar and the cover, placed
 * where the loaded screen keeps them.
 *
 * The cover is here because it is the hero transition's landing target. A bare spinner has none, so
 * the shared element leaving the library grid had nothing to fly into and silently did nothing —
 * the animation exists only if this frame carries the same [bookCoverHeroKey] the grid cell did.
 * [com.calypsan.listenup.client.design.components.BookCoverImage] resolves a cover from [bookId]
 * alone, and the grid has already warmed Coil's memory cache, so it renders on the first frame.
 *
 * Showing the cover you tapped also beats a lone spinner on its own terms: the screen answers "did
 * my tap land?" straight away instead of making you wait to find out.
 */
@Composable
private fun BookDetailLoadingFrame(
    bookId: String,
    onBackClick: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        // Nothing is offered until the book is known, as on iOS: no ⋮ at all.
        BookDetailTopBar(
            onBackClick = onBackClick,
            menu = emptyList(),
            progressBusy = false,
            onMenuItem = {},
            isHeld = false,
        )
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.screenMargin),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The cover ALONE, flat, exactly as BookDetailHeroBand draws it: the same box and corner, so the
            // shared element lands where the loaded screen keeps it.
            BookCoverImage(
                bookId = bookId,
                coverPath = null,
                contentDescription = null,
                heroKey = bookCoverHeroKey(bookId),
                heroClipShape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.size(PhoneHeroCoverSize).clip(MaterialTheme.shapes.extraLarge),
            )
        }
    }
}

/**
 * The confirmation Book Detail is asking for, if any. Only one can be open.
 *
 * [RemoveDownload] forgets the downloaded copy and can be undone by downloading again;
 * [DeleteBookFromDisk] deletes the book and its folder from disk and cannot.
 */
private enum class BookDetailDialog {
    RemoveDownload,
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
@Suppress("LongParameterList", "LongMethod")
@Composable
private fun BookDetailReadyContent(
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
    onBookClick: (bookId: String) -> Unit,
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

    val book = state.book

    // One instance for as long as none of what the actions reach for changes, so a recomposition
    // that only moves `state` does not make both layouts recompose over fresh callbacks.
    val actions =
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
            onBookClick,
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
                onMarkCompleteClick = { dialog = BookDetailDialog.MarkComplete },
                onMarkNotStartedClick = { dialog = BookDetailDialog.MarkNotStarted },
                onRestartClick = { dialog = BookDetailDialog.Restart },
                onAddToShelfClick = { viewModel.showShelfPicker() },
                onAddToCollectionClick = { viewModel.showCollectionPicker() },
                onShareClick = {
                    scope.launch {
                        // The RPC-backed server identity, through the one builder every client shares.
                        bookShareUrl(instanceRepository, book.id)?.let { url ->
                            platformActions.shareText("Check out ${book.title} on ListenUp!\n$url", url)
                        }
                    }
                },
                onDeleteBookClick = {
                    viewModel.clearDeleteError()
                    dialog = BookDetailDialog.DeleteBookFromDisk
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
                onCancelClick = {
                    scope.launch {
                        platformActions.cancelDownload(BookId(bookId))
                    }
                },
                onDeleteClick = { dialog = BookDetailDialog.RemoveDownload },
                onOpenFilesAndAudio = { dialog = BookDetailDialog.FilesAndAudio },
                onOpenDocuments = { dialog = BookDetailDialog.Documents },
                onSeriesClick = onSeriesClick,
                onContributorClick = onContributorClick,
                onGenreClick = onGenreClick,
                onTagClick = onTagClick,
                onMoodClick = onMoodClick,
                onSeeAllReaders = onSeeAllReaders,
                onFindHardcoverMatch = onFindHardcoverMatch,
                onReleaseFromInboxClick = { dialog = BookDetailDialog.ReleaseFromInbox },
                onRestoreToAllBooksClick = { viewModel.restoreToAllBooks() },
                onCollectionClick = onCollectionClick,
                onPlayFromChapter = { startMs -> platformActions.playFromChapter(BookId(bookId), startMs) },
                onBookClick = onBookClick,
            )
        }

    BookDetailContent(
        bookId = bookId,
        state = state,
        actions = actions,
        documents = documents,
        isPreparing = isPreparing,
        lastMatchRow = { modifier -> LastMatchHost(viewModel = viewModel, modifier = modifier) },
        landOnReaders = landOnReaders,
    )

    when (dialog) {
        BookDetailDialog.RemoveDownload -> {
            DeleteDownloadDialog(
                downloadSize = state.downloadStatus.downloadedOrTotalBytes(),
                onConfirm = {
                    scope.launch {
                        platformActions.deleteDownload(BookId(bookId))
                    }
                    dialog = null
                },
                onDismiss = { dialog = null },
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
                    dialog = null
                    viewModel.clearDeleteError()
                },
            )
        }

        BookDetailDialog.MarkNotStarted -> {
            MarkNotStartedDialog(
                onConfirm = {
                    viewModel.discardProgress()
                    dialog = null
                },
                onDismiss = { dialog = null },
            )
        }

        BookDetailDialog.Restart -> {
            RestartBookDialog(
                onConfirm = {
                    viewModel.restartBook()
                    dialog = null
                },
                onDismiss = { dialog = null },
            )
        }

        BookDetailDialog.MarkComplete -> {
            MarkCompleteDialog(
                startedAtMs = state.startedAtMs,
                onConfirm = { startedAt, finishedAt ->
                    viewModel.markComplete(startedAt = startedAt, finishedAt = finishedAt)
                    dialog = null
                },
                onDismiss = { dialog = null },
            )
        }

        // Release asks first (spec §7). Confirming closes the question at once; the held section's
        // Release button carries the in-flight state, and success turns this into the ordinary page.
        BookDetailDialog.ReleaseFromInbox -> {
            if (state.isHeld) {
                ReleaseToEveryoneDialog(
                    bookCount = 1,
                    onConfirm = {
                        dialog = null
                        viewModel.releaseFromInbox()
                    },
                    onDismiss = { dialog = null },
                )
            }
        }

        BookDetailDialog.FilesAndAudio -> {
            FilesAndAudioSheet(audioFiles = book.audioFiles, onDismiss = { dialog = null })
        }

        BookDetailDialog.Documents -> {
            DocumentsSheet(
                documents = documents,
                onOpen = { docId ->
                    viewModel.onOpenDocument(docId)
                    dialog = null
                },
                onDismiss = { dialog = null },
            )
        }

        null -> {
            // No question is open.
        }
    }

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

/**
 * Main content container that handles responsive layout.
 * Uses WideBookDetail for tablets, ImmersiveBookDetail for phones.
 */
@Composable
fun BookDetailContent(
    bookId: String,
    state: BookDetailUiState.Ready,
    actions: BookDetailActions,
    documents: List<BookDocument> = emptyList(),
    isPreparing: Boolean = false,
    /** Book Detail's last-match row ("Details matched … · See what changed · Undo last match"), under the actions. */
    lastMatchRow: @Composable (Modifier) -> Unit = {},
    /** Scroll the Readers section into view once, on arrival (spec §6.4). */
    landOnReaders: Boolean = false,
) {
    val windowSizeClass = currentWindowAdaptiveInfoV2().windowSizeClass

    // Two-pane only at EXPANDED width (~840dp+): the wide hero band + About/Credits + Readers/Chapters
    // columns need real room. At medium width (portrait tablets, unfolded-foldable portrait) the
    // single fluid [ImmersiveBookDetail] column reads better than a cramped two-pane.
    val useTwoPane =
        windowSizeClass.isWidthAtLeastBreakpoint(
            WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND,
        )

    if (useTwoPane) {
        WideBookDetail(
            bookId = bookId,
            state = state,
            actions = actions,
            documents = documents,
            isPreparing = isPreparing,
            lastMatchRow = lastMatchRow,
            landOnReaders = landOnReaders,
        )
    } else {
        ImmersiveBookDetail(
            bookId = bookId,
            state = state,
            actions = actions,
            documents = documents,
            isPreparing = isPreparing,
            lastMatchRow = lastMatchRow,
            landOnReaders = landOnReaders,
        )
    }
}

/** The phone column's Resume unit over its action group (spec §2.9). */
@Composable
private fun PhoneListenUnit(
    state: BookDetailUiState.Ready,
    actions: BookDetailActions,
    isPreparing: Boolean,
    screenPadding: Modifier,
) {
    Column(
        modifier = screenPadding.padding(top = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ResumeUnit(
            readingStage = state.readingStage,
            currentChapter = state.currentChapter,
            timeRemainingMs = state.timeRemainingMs,
            progress = state.progress,
            finishedSpan = state.finishedSpan,
        )
        // Why the primary below is disabled, directly above it (spec §2.9).
        if (state.primaryAction == PrimaryAction.UnavailableOffline) OfflineReason(onRetry = actions.onRetryConnection)
        ListenActionGroup(
            primaryAction = state.primaryAction,
            isPreparing = isPreparing,
            downloadStatus = state.downloadStatus,
            isWaitingForWifi = state.isWaitingForWifi,
            downloadSizeBytes = state.downloadSizeBytes,
            supportsDownloads = LocalDeviceContext.current.supportsDownloads,
            canDownload = state.canDownload,
            labelled = false,
            onPrimaryClick = actions.onPlayClick,
            onDownload = actions.onDownloadClick,
            onCancelDownload = actions.onCancelClick,
            onRemoveDownload = actions.onDeleteClick,
            onAddToShelf = actions.onAddToShelfClick,
        )
    }
}

// =============================================================================
// IMMERSIVE SINGLE-PANE LAYOUT (Phone)
// =============================================================================

/**
 * One item of the phone column, keyed. The column is built by walking [phoneDetailItems], so where Readers sits is read
 * off the same list the column draws, never counted by hand.
 */
internal sealed interface PhoneDetailItem {
    /** The item's key in the column. */
    val key: String

    /** The scanner's flag, for an admin, at the top of the hero. */
    data object ScanWarning : PhoneDetailItem {
        override val key = "scan-warning"
    }

    /** Cover, series path, title, byline and stats. */
    data object Hero : PhoneDetailItem {
        override val key = "hero"
    }

    /** A held book's triage panel, where the action unit would be. */
    data object Held : PhoneDetailItem {
        override val key = "held"
    }

    /** The Resume unit over the action group. */
    data object Listen : PhoneDetailItem {
        override val key = "listen"
    }

    /** Who else is reading: one link to Readers. */
    data object Social : PhoneDetailItem {
        override val key = "social"
    }

    /** Who can't see this book (admins only), directly under the actions (spec §3.2.4 rule 4). */
    data object Visibility : PhoneDetailItem {
        override val key = "visibility"
    }

    /** The last match, with See what changed and Undo. */
    data object LastMatch : PhoneDetailItem {
        override val key = "last-match"
    }

    /** One of the stage-ordered sections. Readers is keyed `readers`: the anchor a landing on it scrolls to. */
    data class Section(
        val section: BookDetailSection,
    ) : PhoneDetailItem {
        override val key: String = section.itemKey()
    }
}

private fun BookDetailSection.itemKey(): String =
    when (this) {
        BookDetailSection.About -> "about"
        BookDetailSection.Ratings -> "ratings"
        BookDetailSection.Hardcover -> "hardcover"
        BookDetailSection.Readers -> READERS_ITEM_KEY
        BookDetailSection.Series -> "series"
        BookDetailSection.Chapters -> "chapters"
        BookDetailSection.Details -> "details"
    }

/** The Readers section's key in the phone column. */
internal const val READERS_ITEM_KEY = "readers"

/**
 * The phone column's items, in the order it draws them (spec §2.4): the admin's scan warning, the hero, the action unit (or the held
 * panel), the social line, the Visibility card, the last match, then the sections in the visit's stage order.
 */
internal fun phoneDetailItems(
    state: BookDetailUiState.Ready,
    hasSocialLine: Boolean,
): List<PhoneDetailItem> =
    buildList {
        if (state.showScanWarning) add(PhoneDetailItem.ScanWarning)
        add(PhoneDetailItem.Hero)
        when {
            state.isHeld -> add(PhoneDetailItem.Held)
            state.isPlaybackAvailable -> add(PhoneDetailItem.Listen)
        }
        if (!state.isHeld && hasSocialLine) add(PhoneDetailItem.Social)
        if (!state.isHeld && state.visibility != null) add(PhoneDetailItem.Visibility)
        add(PhoneDetailItem.LastMatch)
        state.shownSections().forEach { add(PhoneDetailItem.Section(it)) }
    }

/** Where Readers sits in [items], or null when the column shows none. */
internal fun readersItemIndex(items: List<PhoneDetailItem>): Int? =
    items.indexOfFirst { it is PhoneDetailItem.Section && it.section == BookDetailSection.Readers }.takeIf { it >= 0 }

/**
 * Compact (phone) Book Detail column.
 *
 * A plain, untitled [BookDetailTopBar] (Back to Library, More) is hoisted above the scroll; the [LazyColumn] then
 * carries [phoneDetailItems]: the admin's scan warning, the [BookDetailHeroBand], the [ResumeUnit] over the [ListenActionGroup]
 * (or the held triage panel), the [HeroSocialLine], the Visibility card, the last match, then the sections in the
 * visit's stage order. The social line scrolls the column to Readers.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
private fun ImmersiveBookDetail(
    bookId: String,
    state: BookDetailUiState.Ready,
    actions: BookDetailActions,
    documents: List<BookDocument> = emptyList(),
    isPreparing: Boolean = false,
    lastMatchRow: @Composable (Modifier) -> Unit = {},
    landOnReaders: Boolean = false,
) {
    var isDescriptionExpanded by rememberSaveable { mutableStateOf(false) }
    // Full-cast overlay target — set by a folded hero author/narrator line, cleared on dismiss.
    var castRole by remember { mutableStateOf<CastRole?>(null) }

    val book = state.book
    val screenPadding = Modifier.padding(horizontal = Spacing.screenMargin)
    // A held book is triage-only: no social line and no Readers, so its readers are never asked for.
    val readers = if (state.isHeld) PageReaders(hasArrived = false, summary = null) else rememberPageReaders(bookId)
    val summary = readers.summary
    val items = phoneDetailItems(state, hasSocialLine = summary != null)
    val readersIndex = readersItemIndex(items)
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // Home's finishers line lands here (spec §6.4), once per entry: a return to this screen (or a rotation) keeps
    // wherever the reader scrolled to. The index is read off the same list the column draws.
    var landedOnReaders by rememberSaveable(bookId) { mutableStateOf(false) }
    // Focus goes to the Readers heading on a landing, and on every social-line tap: TalkBack follows the jump.
    var focusReadersHeading by rememberSaveable(bookId) { mutableStateOf(landOnReaders) }
    // Once the readers have arrived: until then the section has no height, and the social line above it is not yet
    // in the list, so the index would move after the scroll.
    LaunchedEffect(landOnReaders, readersIndex, readers.hasArrived) {
        if (landOnReaders && !landedOnReaders && readersIndex != null && readers.hasArrived) {
            landedOnReaders = true
            listState.animateScrollToItem(readersIndex)
        }
    }
    val description = DescriptionExpansion(isDescriptionExpanded) { isDescriptionExpanded = !isDescriptionExpanded }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        val menu = remember(state) { bookDetailMoreMenu(state) }
        BookDetailTopBar(
            onBackClick = actions.onBackClick,
            menu = menu,
            progressBusy = state.isChangingProgress,
            onMenuItem = actions::perform,
            isHeld = state.isHeld,
        )

        val playerInset = LocalNowPlayingInsets.current.asPaddingValues().calculateBottomPadding()
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp + playerInset),
        ) {
            items.forEach { entry ->
                item(key = entry.key) {
                    when (entry) {
                        // The scanner's flag, for an admin: Review opens Files & audio.
                        PhoneDetailItem.ScanWarning -> {
                            ScanWarningBanner(
                                onReview = actions.onOpenFilesAndAudio,
                                modifier = screenPadding.padding(vertical = 8.dp),
                            )
                        }

                        // Identity — cover, series path, title, subtitle, byline and the quick-stat chips.
                        PhoneDetailItem.Hero -> {
                            BookDetailHeroBand(
                                coverPath = book.coverPath,
                                coverHash = book.coverHash,
                                bookId = bookId,
                                title = book.title,
                                subtitle = state.subtitle,
                                seriesPaths = state.seriesPaths,
                                authors = book.authors,
                                narrators = book.narrators,
                                durationMs = book.duration,
                                year = state.year,
                                abridged = book.abridged,
                                onContributorClick = actions.onContributorClick,
                                onSeriesClick = actions.onSeriesClick,
                                onShowCast = { castRole = it },
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }

                        // A held book is triage-only (spec §8, §10): the held section — Release, Edit, Match, Edit
                        // chapters — stands where the action unit would, under the title.
                        PhoneDetailItem.Held -> {
                            HeldForReviewSection(
                                isReleasing = state.isReleasingFromInbox,
                                onReleaseClick = actions.onReleaseFromInboxClick,
                                onEditClick = actions.onEditClick,
                                onMatchClick = actions.onFindMetadataClick,
                                onEditChaptersClick = actions.onEditChaptersClick,
                                modifier = screenPadding.padding(top = 20.dp),
                            )
                        }

                        // The Resume unit directly over the action group (spec §2.9): where you are, then what next.
                        PhoneDetailItem.Listen -> {
                            PhoneListenUnit(
                                state = state,
                                actions = actions,
                                isPreparing = isPreparing,
                                screenPadding = screenPadding,
                            )
                        }

                        // Who else is reading (spec §2.10): a tap brings the Readers section into view.
                        PhoneDetailItem.Social -> {
                            if (summary != null) {
                                HeroSocialLine(
                                    summary = summary,
                                    ringColor = MaterialTheme.colorScheme.surface,
                                    onOpenReaders = {
                                        readersIndex?.let { index ->
                                            scope.launch { listState.animateScrollToItem(index) }
                                        }
                                        focusReadersHeading = true
                                    },
                                    modifier = screenPadding.padding(top = 12.dp),
                                )
                            }
                        }

                        // Who can't see it — admins only; never in the triage layout (the held section says it).
                        PhoneDetailItem.Visibility -> {
                            state.visibility?.let { visibility ->
                                BookVisibilitySection(
                                    visibility = visibility,
                                    isRestoring = state.isRestoringToAllBooks,
                                    onCollectionClick = actions.onCollectionClick,
                                    onShowToAllMembers = actions.onRestoreToAllBooksClick,
                                    onAddToCollection = actions.onAddToCollectionClick,
                                    modifier = screenPadding.padding(top = 16.dp),
                                )
                            }
                        }

                        // The last match, with See what changed and Undo — under the actions (A-04).
                        PhoneDetailItem.LastMatch -> {
                            lastMatchRow(screenPadding.padding(top = 16.dp))
                        }

                        is PhoneDetailItem.Section -> {
                            BookDetailSectionContent(
                                section = entry.section,
                                bookId = bookId,
                                state = state,
                                actions = actions,
                                documents = documents,
                                isWide = false,
                                description = description,
                                onShowAllCredits = { castRole = CastRole.All },
                                modifier = screenPadding.padding(top = 16.dp),
                                readersLanding =
                                    ReadersLanding(
                                        focusHeading = focusReadersHeading,
                                        onHeadingFocused = { focusReadersHeading = false },
                                    ),
                            )
                        }
                    }
                }
            }
        }

        castRole?.let { role ->
            FullCastSheetFor(
                role = role,
                authors = book.authors,
                narrators = book.narrators,
                onContributorClick = actions.onContributorClick,
                onDismiss = { castRole = null },
                everyone = book.allContributors,
            )
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
