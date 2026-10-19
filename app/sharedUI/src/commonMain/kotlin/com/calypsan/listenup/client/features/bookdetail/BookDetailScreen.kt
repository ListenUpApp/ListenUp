package com.calypsan.listenup.client.features.bookdetail

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.design.components.LocalNowPlayingInsets
import com.calypsan.listenup.client.features.match.LastMatchHost
import com.calypsan.listenup.client.features.match.MatchReceiptHost
import com.calypsan.listenup.client.presentation.bookdetail.LastMatchEvent
import listenup.composeapp.generated.resources.match_undo_expired
import listenup.composeapp.generated.resources.match_undone
import com.calypsan.listenup.client.design.components.LocalSnackbarHostState
import com.calypsan.listenup.client.design.theme.DisplayFontFamily
import com.calypsan.listenup.client.design.components.BookCoverImage
import com.calypsan.listenup.client.design.components.listenUpOutlinedBorder
import com.calypsan.listenup.client.design.transitions.bookCoverHeroKey
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.DownloadOutcome
import com.calypsan.listenup.client.features.library.CollectionPickerSheet
import com.calypsan.listenup.client.features.library.ShelfPickerSheet
import com.calypsan.listenup.client.features.bookdetail.components.AboutSection
import com.calypsan.listenup.client.features.bookdetail.components.BookDetailTopBar
import com.calypsan.listenup.client.features.admin.inbox.ReleaseToEveryoneDialog
import com.calypsan.listenup.client.features.bookdetail.components.BookRatingBlock
import com.calypsan.listenup.client.features.bookdetail.components.BookVisibilitySection
import com.calypsan.listenup.client.features.bookdetail.components.HeldForReviewSection
import com.calypsan.listenup.client.features.bookdetail.components.BookHardcoverSection
import com.calypsan.listenup.client.features.bookdetail.components.BookReadersSection
import com.calypsan.listenup.client.features.bookdetail.components.ChapterListItem
import com.calypsan.listenup.client.features.bookdetail.components.ChaptersHeader
import com.calypsan.listenup.client.features.contributors.CastRole
import com.calypsan.listenup.client.features.bookdetail.components.BookDetailHeroBand
import com.calypsan.listenup.client.features.bookdetail.components.DetailsSection
import com.calypsan.listenup.client.features.contributors.FullCastSheetFor
import com.calypsan.listenup.client.features.bookdetail.components.MarkCompleteDialog
import com.calypsan.listenup.client.features.bookdetail.components.MarkNotStartedDialog
import com.calypsan.listenup.client.features.bookdetail.components.OfflineBanner
import com.calypsan.listenup.client.features.bookdetail.components.ListenActionGroup
import com.calypsan.listenup.client.features.bookdetail.components.PhoneHeroCoverSize
import com.calypsan.listenup.client.features.bookdetail.components.RestartBookDialog
import com.calypsan.listenup.client.features.bookdetail.components.ResumeUnit
import com.calypsan.listenup.client.features.bookdetail.components.WideBookDetail
import com.calypsan.listenup.client.domain.model.BookDocument
import com.calypsan.listenup.client.playback.PlayerSession
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailNavAction
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailUiState
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailViewModel
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import com.calypsan.listenup.client.presentation.error.localized
import com.calypsan.listenup.client.presentation.error.localizedString
import com.calypsan.listenup.client.share.ShareLinkCodec
import com.calypsan.listenup.client.share.ShareTarget
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_document_meta
import listenup.composeapp.generated.resources.book_detail_document_viewer_coming_soon
import listenup.composeapp.generated.resources.book_detail_insufficient_storage
import listenup.composeapp.generated.resources.book_detail_scan_warning
import listenup.composeapp.generated.resources.book_detail_supplementary_materials
import listenup.composeapp.generated.resources.book_show_all_chapters
import com.calypsan.listenup.client.design.theme.ContentShapes
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
    onSeeAllReaders: (bookId: String) -> Unit = {},
    onFindHardcoverMatch: ((bookId: String) -> Unit)? = null,
    /** Opens an admin collection from the Visibility card; null shows the names as plain labels. */
    onCollectionClick: ((collectionId: String) -> Unit)? = null,
    onOpenDocumentViewer: (localPath: String) -> Unit = {},
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
                    onOpenDocumentViewer(action.localPath)
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
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = s.error.localized(),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
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
        BookDetailTopBar(
            isComplete = false,
            hasProgress = false,
            isAdmin = false,
            canEditMetadata = false,
            onBackClick = onBackClick,
            onEditClick = {},
            onFindMetadataClick = {},
            onEditChaptersClick = {},
            onMarkCompleteClick = {},
            onMarkNotStartedClick = {},
            onRestartClick = {},
            onAddToCollectionClick = {},
            onShareClick = {},
            onDeleteClick = {},
            // Every callback above is a stub while the book loads; disable the rows so none
            // of them confirms a tap that does nothing.
            actionsEnabled = false,
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
                        // Use the RPC-backed server identity, not the legacy `getInstance` REST path: the
                        // Kotlin server responds a bare (non-enveloped) body there, so decoding it as
                        // `ApiResponse<Instance>` threw on every share. `getServerInfo` is pure RPC and
                        // carries the `remoteUrl` + `instanceId` the share link needs. Mirrors iOS #1045.
                        val result = instanceRepository.getServerInfo()
                        if (result is AppResult.Success) {
                            val info = result.data
                            val url =
                                ShareLinkCodec.encode(
                                    ShareTarget.Book(
                                        bookId = book.id,
                                        serverInstanceId = info.instanceId,
                                        serverUrl = info.remoteUrl?.trimEnd('/'),
                                    ),
                                )
                            val text = "Check out ${book.title} on ListenUp!\n$url"
                            platformActions.shareText(text, url)
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
                bookTitle = book.title,
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

/**
 * The phone column's leading items, keyed, in the order the column draws them. The column is built by walking this
 * list, so [readersItemIndex] is read off the same order rather than counted by hand.
 */
internal enum class PhoneDetailItem(
    val key: String,
) {
    Offline("offline"),
    ScanWarning("scan-warning"),
    Hero("hero"),
    Held("held"),
    Listen("listen"),
    LastMatch("last-match"),
    About("about"),
    Visibility("visibility"),
    Rating("rating"),
    Readers("readers"),
    Hardcover("hardcover"),
}

/**
 * Which [PhoneDetailItem]s a book's phone column shows: the advisories, the hero, the held panel or the action unit,
 * the last match and About, then — never for a held book (spec §9) — the admins' visibility card, rating, readers and
 * Hardcover.
 */
internal fun phoneDetailItems(
    showServerWarning: Boolean,
    isHeld: Boolean,
    isPlaybackAvailable: Boolean,
    hasVisibility: Boolean,
): List<PhoneDetailItem> =
    buildList {
        if (showServerWarning) add(PhoneDetailItem.Offline)
        add(PhoneDetailItem.ScanWarning)
        add(PhoneDetailItem.Hero)
        if (isHeld) {
            add(PhoneDetailItem.Held)
        } else if (isPlaybackAvailable) {
            add(PhoneDetailItem.Listen)
        }
        add(PhoneDetailItem.LastMatch)
        add(PhoneDetailItem.About)
        if (!isHeld) {
            if (hasVisibility) add(PhoneDetailItem.Visibility)
            add(PhoneDetailItem.Rating)
            add(PhoneDetailItem.Readers)
            add(PhoneDetailItem.Hardcover)
        }
    }

/** Where Readers sits in [items] — the column's own index for it — or null when the column shows none. */
internal fun readersItemIndex(items: List<PhoneDetailItem>): Int? =
    items.indexOf(PhoneDetailItem.Readers).takeIf { it >= 0 }

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
 * Compact (phone) Book Detail column.
 *
 * A plain, untitled [BookDetailTopBar] (Back to Library, More) is hoisted above the scroll; the [LazyColumn] then
 * carries, in order: offline + scan advisories, the [BookDetailHeroBand] (cover, series path, title, byline, stat
 * chips), the [ResumeUnit] over the [ListenActionGroup] (or the held triage panel), then the sections.
 */
@Suppress("LongMethod")
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
    var isChaptersExpanded by rememberSaveable { mutableStateOf(false) }
    // Full-cast overlay target — set by a folded hero author/narrator line, cleared on dismiss.
    var castRole by remember { mutableStateOf<CastRole?>(null) }

    val book = state.book
    val screenPadding = Modifier.padding(horizontal = Spacing.screenMargin)
    val listState = rememberLazyListState()
    val leadingItems =
        phoneDetailItems(
            showServerWarning = state.showServerWarning,
            isHeld = state.isHeld,
            isPlaybackAvailable = state.isPlaybackAvailable,
            hasVisibility = state.visibility != null,
        )
    val readersIndex = readersItemIndex(leadingItems)
    // Once per entry: a return to this screen (or a rotation) keeps wherever the reader scrolled to.
    var landedOnReaders by rememberSaveable(bookId) { mutableStateOf(false) }
    var focusedReaders by rememberSaveable(bookId) { mutableStateOf(false) }
    LaunchedEffect(landOnReaders, readersIndex) {
        if (landOnReaders && !landedOnReaders && readersIndex != null) {
            landedOnReaders = true
            listState.animateScrollToItem(readersIndex)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        BookDetailTopBar(
            isComplete = state.isComplete,
            hasProgress = state.progress != null,
            isAdmin = state.isAdmin,
            canEditMetadata = state.canEditMetadata,
            onBackClick = actions.onBackClick,
            onEditClick = actions.onEditClick,
            onFindMetadataClick = actions.onFindMetadataClick,
            onEditChaptersClick = actions.onEditChaptersClick,
            onMarkCompleteClick = actions.onMarkCompleteClick,
            onMarkNotStartedClick = actions.onMarkNotStartedClick,
            onRestartClick = actions.onRestartClick,
            onAddToCollectionClick = actions.onAddToCollectionClick,
            onShareClick = actions.onShareClick,
            onDeleteClick = actions.onDeleteBookClick,
            showActions = !state.isHeld,
        )

        val playerInset = LocalNowPlayingInsets.current.asPaddingValues().calculateBottomPadding()
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(bottom = 32.dp + playerInset),
        ) {
            // The leading items, in [phoneDetailItems]' order: the Readers landing reads its index off the same list.
            leadingItems.forEach { entry ->
                item(key = entry.key) {
                    when (entry) {
                        // Offline advisory — streaming unavailable, downloads still play.
                        PhoneDetailItem.Offline -> {
                            OfflineBanner(
                                onRetryClick = actions.onRetryConnection,
                                compact = true,
                                modifier = screenPadding.padding(vertical = 8.dp),
                            )
                        }

                        // Scan-warning advisory — heads-up when the scanner flagged this book's files.
                        PhoneDetailItem.ScanWarning -> {
                            BookDetailScanWarning(
                                hasScanWarning = state.hasScanWarning,
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

                        // A held book is triage-only (spec §8, §10): the held section — Release, Edit, Match,
                        // Edit chapters — stands where the action unit would, under the title, and nothing below
                        // offers anything else. Match and Edit chapters reuse the overflow menu's routes.
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

                        // The Resume unit directly over the action group (spec §2.9): where you are, then what to do.
                        PhoneDetailItem.Listen -> {
                            PhoneListenUnit(
                                state = state,
                                actions = actions,
                                isPreparing = isPreparing,
                                screenPadding = screenPadding,
                            )
                        }

                        // The last match, with See what changed and Undo — under the actions (A-04).
                        PhoneDetailItem.LastMatch -> {
                            lastMatchRow(screenPadding.padding(top = 16.dp))
                        }

                        // About — description + Genres + Tags, frameless.
                        PhoneDetailItem.About -> {
                            AboutSection(
                                description = state.descriptionText,
                                genres = state.genres,
                                tags = state.tags,
                                moods = state.moods,
                                isLoadingTags = state.isLoadingTags,
                                isCard = false,
                                isDescriptionExpanded = isDescriptionExpanded,
                                onToggleDescriptionExpanded = { isDescriptionExpanded = !isDescriptionExpanded },
                                onGenreClick = { genre -> actions.onGenreClick(genre.id) },
                                onTagClick = { tag -> actions.onTagClick(tag.id, tag.displayName()) },
                                onMoodClick = { mood -> actions.onMoodClick(mood.id, mood.displayName()) },
                                creditsSlot = null,
                                modifier = screenPadding.padding(top = 24.dp),
                            )
                        }

                        // Who can't see it — straight after About, before Rating (canvas). Admins only.
                        PhoneDetailItem.Visibility -> {
                            state.visibility?.let { visibility ->
                                BookVisibilitySection(
                                    visibility = visibility,
                                    isRestoring = state.isRestoringToAllBooks,
                                    onCollectionClick = actions.onCollectionClick,
                                    onShowToAllMembers = actions.onRestoreToAllBooksClick,
                                    onAddToCollection = actions.onAddToCollectionClick,
                                    modifier = screenPadding.padding(vertical = 8.dp),
                                )
                            }
                        }

                        // Rating — your listeners' stars and your own, right above the people who left them.
                        PhoneDetailItem.Rating -> {
                            BookRatingBlock(bookId = bookId, modifier = screenPadding.padding(vertical = 8.dp))
                        }

                        // Readers — social reading activity; the finishers line lands here (spec §6.4).
                        PhoneDetailItem.Readers -> {
                            BookReadersSection(
                                bookId = bookId,
                                onUserClick = actions.onUserProfileClick,
                                onSeeAllClick = actions.onSeeAllReaders,
                                focusHeadingOnArrival = landOnReaders && !focusedReaders,
                                onHeadingFocused = { focusedReaders = true },
                                modifier = screenPadding.padding(vertical = 8.dp),
                            )
                        }

                        // Hardcover — where this book stands on the user's Hardcover shelf; nothing when not connected.
                        PhoneDetailItem.Hardcover -> {
                            BookHardcoverSection(
                                bookId = bookId,
                                onFindMatch = actions.onFindHardcoverMatch,
                                modifier = screenPadding.padding(vertical = 8.dp),
                            )
                        }
                    }
                }
            }

            // Chapters — deep dive.
            item {
                Spacer(modifier = Modifier.height(16.dp))
                ChaptersHeader(
                    chapterCount = state.chapters.size,
                    modifier = screenPadding.padding(vertical = 8.dp),
                )
            }

            val displayedChapters = if (isChaptersExpanded) state.chapters else state.chapters.take(5)
            itemsIndexed(
                items = displayedChapters,
                key = { _, chapter -> chapter.id },
            ) { index, chapter ->
                ChapterListItem(
                    chapter = chapter,
                    chapterNumber = index + 1,
                    modifier = screenPadding,
                    // TODO(book-detail): mark current chapter once progress→chapter mapping is available.
                    isCurrent = false,
                    showDivider = index < displayedChapters.lastIndex,
                )
            }

            if (state.chapters.size > 5 && !isChaptersExpanded) {
                item {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        OutlinedButton(
                            border = listenUpOutlinedBorder(),
                            onClick = { isChaptersExpanded = true },
                            shape = ContentShapes.card,
                        ) {
                            Text(stringResource(Res.string.book_show_all_chapters, state.chapters.size))
                        }
                    }
                }
            }

            // Supplementary materials — PDFs and other documents attached to this book.
            if (documents.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    SupplementaryMaterialsSection(
                        documents = documents,
                        onOpenDocument = actions.onOpenDocument,
                        modifier = screenPadding,
                    )
                }
            }

            // Details — publisher / published / language / format, then contributor credits.
            item {
                Spacer(modifier = Modifier.height(16.dp))
                DetailsSection(
                    publisher = book.publisher,
                    publishYear = book.publishYear,
                    language = book.language,
                    audioFiles = book.audioFiles,
                    credits = book.allContributors,
                    onContributorClick = actions.onContributorClick,
                    modifier = screenPadding.padding(top = 8.dp),
                    addedAt = state.addedAt,
                )
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

/**
 * Advisory banner shown when the scanner flagged a problem with this book's
 * files. Renders nothing when [hasScanWarning] is `false`, so callers can place
 * it unconditionally; the gate lives here.
 */
@Composable
fun BookDetailScanWarning(
    hasScanWarning: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!hasScanWarning) return

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.errorContainer,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = stringResource(Res.string.book_detail_scan_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

/**
 * "Supplementary materials" section — a header followed by one tappable card per document.
 *
 * Rendered only when [documents] is non-empty (the caller gates on this). Each card shows a tinted
 * icon tile, the file's basename, format + size, and a chevron. Tapping calls [onOpenDocument] with
 * the document's id — the ViewModel handles format dispatch.
 */
@Composable
internal fun SupplementaryMaterialsSection(
    documents: List<BookDocument>,
    onOpenDocument: (docId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Section header — mirrors ChaptersHeader style.
        Text(
            text = stringResource(Res.string.book_detail_supplementary_materials),
            style =
                MaterialTheme.typography.titleLarge.copy(
                    fontFamily = DisplayFontFamily,
                    fontWeight = FontWeight.SemiBold,
                ),
            modifier = Modifier.padding(vertical = 8.dp),
        )

        documents.forEach { doc ->
            DocumentCard(doc = doc, onClick = { onOpenDocument(doc.id) })
        }
    }
}

/**
 * Tappable format card for a single supplementary document: tinted icon tile, filename basename,
 * format + size label, and a trailing chevron.
 */
@Composable
private fun DocumentCard(
    doc: BookDocument,
    onClick: () -> Unit,
) {
    val haptics = LocalHaptics.current
    Surface(
        onClick = {
            haptics.press()
            onClick()
        },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().semantics { role = Role.Button },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(44.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = doc.filename.substringAfterLast('/'),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text =
                        stringResource(
                            Res.string.book_detail_document_meta,
                            doc.format.uppercase(),
                            formatFileSize(doc.size),
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
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
