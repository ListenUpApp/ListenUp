package com.calypsan.listenup.client.features.bookdetail

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
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
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import com.calypsan.listenup.client.design.components.LocalNowPlayingInsets
import com.calypsan.listenup.client.design.components.LocalSnackbarHostState
import com.calypsan.listenup.client.design.theme.DisplayFontFamily
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.DownloadOutcome
import com.calypsan.listenup.client.domain.model.mostSpecificGenre
import com.calypsan.listenup.client.features.library.CollectionPickerSheet
import com.calypsan.listenup.client.features.library.ShelfPickerSheet
import com.calypsan.listenup.client.features.bookdetail.components.AboutSection
import com.calypsan.listenup.client.features.bookdetail.components.BookDetailTopBar
import com.calypsan.listenup.client.features.bookdetail.components.BookReadersSection
import com.calypsan.listenup.client.features.bookdetail.components.ChapterListItem
import com.calypsan.listenup.client.features.bookdetail.components.ChaptersHeader
import com.calypsan.listenup.client.features.contributors.CastRole
import com.calypsan.listenup.client.features.bookdetail.components.CompactHero
import com.calypsan.listenup.client.features.bookdetail.components.DetailsSection
import com.calypsan.listenup.client.features.contributors.FullCastSheetFor
import com.calypsan.listenup.client.features.bookdetail.components.MarkCompleteDialog
import com.calypsan.listenup.client.features.bookdetail.components.MarkNotStartedDialog
import com.calypsan.listenup.client.features.bookdetail.components.OfflineBanner
import com.calypsan.listenup.client.features.bookdetail.components.PrimaryActionsSection
import com.calypsan.listenup.client.features.bookdetail.components.StatsRow
import com.calypsan.listenup.client.features.bookdetail.components.WideBookDetail
import com.calypsan.listenup.client.domain.model.BookDocument
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
    onMetadataSearchClick: (bookId: String) -> Unit,
    onSeriesClick: (seriesId: String) -> Unit,
    onContributorClick: (contributorId: String) -> Unit,
    onTagClick: (tagId: String, tagName: String) -> Unit,
    onMoodClick: (moodId: String, moodName: String) -> Unit,
    onUserProfileClick: (userId: String) -> Unit,
    onSeeAllReaders: (bookId: String) -> Unit = {},
    onOpenDocumentViewer: (localPath: String) -> Unit = {},
    viewModel: BookDetailViewModel = koinViewModel(),
) {
    LaunchedEffect(bookId) {
        viewModel.loadBook(bookId)
    }

    val snackbarHostState = LocalSnackbarHostState.current
    val viewerComingSoonLabel = stringResource(Res.string.book_detail_document_viewer_coming_soon)

    // Consume one-shot navigation events from the ViewModel.
    LaunchedEffect(viewModel) {
        viewModel.navActions.collect { action ->
            when (action) {
                is BookDetailNavAction.OpenDocumentViewer -> onOpenDocumentViewer(action.localPath)
                is BookDetailNavAction.ShowViewerComingSoon -> snackbarHostState.showSnackbar(viewerComingSoonLabel)
            }
        }
    }

    val state by viewModel.state.collectAsStateWithLifecycle()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        when (val s = state) {
            is BookDetailUiState.Loading -> {
                Box(modifier = Modifier.fillMaxSize()) {
                    ListenUpLoadingIndicator(modifier = Modifier.align(Alignment.Center))
                }
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
                BookDetailReadyContent(
                    bookId = bookId,
                    state = s,
                    viewModel = viewModel,
                    onBackClick = onBackClick,
                    onEditClick = onEditClick,
                    onMetadataSearchClick = onMetadataSearchClick,
                    onSeriesClick = onSeriesClick,
                    onContributorClick = onContributorClick,
                    onTagClick = onTagClick,
                    onMoodClick = onMoodClick,
                    onUserProfileClick = onUserProfileClick,
                    onSeeAllReaders = onSeeAllReaders,
                )
            }
        }
    }
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
    onMetadataSearchClick: (bookId: String) -> Unit,
    onSeriesClick: (seriesId: String) -> Unit,
    onContributorClick: (contributorId: String) -> Unit,
    onTagClick: (tagId: String, tagName: String) -> Unit,
    onMoodClick: (moodId: String, moodName: String) -> Unit,
    onUserProfileClick: (userId: String) -> Unit,
    onSeeAllReaders: (bookId: String) -> Unit,
) {
    val platformActions: BookDetailPlatformActions = koinInject()
    val instanceRepository: InstanceRepository = koinInject()
    val scope = rememberCoroutineScope()
    val snackbarHostState = LocalSnackbarHostState.current
    val documents by viewModel.documents.collectAsStateWithLifecycle()

    var showDeleteDialog by remember { mutableStateOf(false) }
    var showMarkCompleteDialog by remember { mutableStateOf(false) }
    var showMarkNotStartedDialog by remember { mutableStateOf(false) }

    // Callback for opening metadata search
    val onFindMetadataClick: () -> Unit = {
        onMetadataSearchClick(bookId)
    }

    val book = state.book
    val hasProgress = state.progress != null

    BookDetailContent(
        bookId = bookId,
        state = state,
        documents = documents,
        onOpenDocument = { docId -> viewModel.onOpenDocument(docId) },
        downloadStatus = state.downloadStatus,
        isComplete = state.isComplete,
        hasProgress = hasProgress,
        isAdmin = state.isAdmin,
        isWaitingForWifi = state.isWaitingForWifi,
        showPlaybackActions = state.isPlaybackAvailable,
        onBackClick = onBackClick,
        onEditClick = { onEditClick(bookId) },
        onFindMetadataClick = onFindMetadataClick,
        onMarkCompleteClick = { showMarkCompleteDialog = true },
        onMarkNotStartedClick = { showMarkNotStartedDialog = true },
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
        onDeleteBookClick = { /* TODO: Implement */ },
        onPlayClick = { platformActions.playBook(BookId(bookId)) },
        canPlay = state.canPlay,
        canDownload = state.canDownload,
        showServerWarning = state.showServerWarning,
        onRetryConnection = { viewModel.retryConnection() },
        onPlayDisabledClick = {
            scope.launch {
                snackbarHostState.showSnackbar(
                    "Server is unreachable. Connect to your server to stream this book, or download it for offline playback.",
                )
            }
        },
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
        onDeleteClick = { showDeleteDialog = true },
        onSeriesClick = onSeriesClick,
        onContributorClick = onContributorClick,
        onTagClick = onTagClick,
        onMoodClick = onMoodClick,
        onSeeAllReaders = onSeeAllReaders,
    )

    if (showDeleteDialog) {
        DeleteDownloadDialog(
            bookTitle = book.title,
            downloadSize = state.downloadStatus.downloadedOrTotalBytes(),
            onConfirm = {
                scope.launch {
                    platformActions.deleteDownload(BookId(bookId))
                }
                showDeleteDialog = false
            },
            onDismiss = { showDeleteDialog = false },
        )
    }

    if (showMarkNotStartedDialog) {
        MarkNotStartedDialog(
            onConfirm = {
                viewModel.discardProgress()
                showMarkNotStartedDialog = false
            },
            onDismiss = { showMarkNotStartedDialog = false },
        )
    }

    if (showMarkCompleteDialog) {
        MarkCompleteDialog(
            startedAtMs = state.startedAtMs,
            onConfirm = { startedAt, finishedAt ->
                viewModel.markComplete(startedAt = startedAt, finishedAt = finishedAt)
                showMarkCompleteDialog = false
            },
            onDismiss = { showMarkCompleteDialog = false },
        )
    }

    if (state.showShelfPicker) {
        val myShelves by viewModel.myShelves.collectAsStateWithLifecycle()

        ShelfPickerSheet(
            shelves = myShelves,
            selectedBookCount = 1,
            onShelfSelected = { shelfId -> viewModel.addBookToShelf(shelfId) },
            onCreateAndAddToShelf = { name -> viewModel.createShelfAndAddBook(name) },
            onDismiss = { viewModel.hideShelfPicker() },
            isLoading = state.isAddingToShelf,
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
@Suppress("LongParameterList")
@Composable
fun BookDetailContent(
    bookId: String,
    state: BookDetailUiState.Ready,
    documents: List<BookDocument> = emptyList(),
    onOpenDocument: (docId: String) -> Unit = {},
    downloadStatus: BookDownloadStatus,
    isComplete: Boolean,
    hasProgress: Boolean,
    isAdmin: Boolean,
    isWaitingForWifi: Boolean,
    showPlaybackActions: Boolean,
    onBackClick: () -> Unit,
    onEditClick: () -> Unit,
    onFindMetadataClick: () -> Unit,
    onMarkCompleteClick: () -> Unit,
    onMarkNotStartedClick: () -> Unit,
    onAddToShelfClick: () -> Unit,
    onAddToCollectionClick: () -> Unit,
    onShareClick: () -> Unit,
    onDeleteBookClick: () -> Unit,
    onPlayClick: () -> Unit,
    canPlay: Boolean,
    canDownload: Boolean,
    showServerWarning: Boolean,
    onRetryConnection: () -> Unit,
    onPlayDisabledClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onCancelClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onSeriesClick: (seriesId: String) -> Unit,
    onContributorClick: (contributorId: String) -> Unit,
    onTagClick: (tagId: String, tagName: String) -> Unit,
    onMoodClick: (moodId: String, moodName: String) -> Unit,
    onUserProfileClick: (userId: String) -> Unit,
    onSeeAllReaders: (bookId: String) -> Unit = {},
) {
    val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass

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
            documents = documents,
            onOpenDocument = onOpenDocument,
            downloadStatus = downloadStatus,
            isComplete = isComplete,
            hasProgress = hasProgress,
            isAdmin = isAdmin,
            isWaitingForWifi = isWaitingForWifi,
            showPlaybackActions = showPlaybackActions,
            onBackClick = onBackClick,
            onEditClick = onEditClick,
            onFindMetadataClick = onFindMetadataClick,
            onMarkCompleteClick = onMarkCompleteClick,
            onMarkNotStartedClick = onMarkNotStartedClick,
            onAddToShelfClick = onAddToShelfClick,
            onAddToCollectionClick = onAddToCollectionClick,
            onShareClick = onShareClick,
            onDeleteBookClick = onDeleteBookClick,
            onPlayClick = onPlayClick,
            onDownloadClick = onDownloadClick,
            onCancelClick = onCancelClick,
            onDeleteClick = onDeleteClick,
            playEnabled = canPlay,
            downloadEnabled = canDownload,
            showServerWarning = showServerWarning,
            onRetryConnection = onRetryConnection,
            onPlayDisabledClick = onPlayDisabledClick,
            onSeriesClick = onSeriesClick,
            onContributorClick = onContributorClick,
            onTagClick = onTagClick,
            onMoodClick = onMoodClick,
            onUserProfileClick = onUserProfileClick,
            onSeeAllReaders = onSeeAllReaders,
        )
    } else {
        ImmersiveBookDetail(
            bookId = bookId,
            state = state,
            documents = documents,
            onOpenDocument = onOpenDocument,
            downloadStatus = downloadStatus,
            isComplete = isComplete,
            hasProgress = hasProgress,
            isAdmin = isAdmin,
            isWaitingForWifi = isWaitingForWifi,
            showPlaybackActions = showPlaybackActions,
            onBackClick = onBackClick,
            onEditClick = onEditClick,
            onFindMetadataClick = onFindMetadataClick,
            onMarkCompleteClick = onMarkCompleteClick,
            onMarkNotStartedClick = onMarkNotStartedClick,
            onAddToShelfClick = onAddToShelfClick,
            onAddToCollectionClick = onAddToCollectionClick,
            onShareClick = onShareClick,
            onDeleteBookClick = onDeleteBookClick,
            onPlayClick = onPlayClick,
            canPlay = canPlay,
            canDownload = canDownload,
            showServerWarning = showServerWarning,
            onRetryConnection = onRetryConnection,
            onPlayDisabledClick = onPlayDisabledClick,
            onDownloadClick = onDownloadClick,
            onCancelClick = onCancelClick,
            onDeleteClick = onDeleteClick,
            onSeriesClick = onSeriesClick,
            onContributorClick = onContributorClick,
            onTagClick = onTagClick,
            onMoodClick = onMoodClick,
            onUserProfileClick = onUserProfileClick,
            onSeeAllReaders = onSeeAllReaders,
        )
    }
}

// =============================================================================
// IMMERSIVE SINGLE-PANE LAYOUT (Phone)
// =============================================================================

/**
 * Compact (phone) Book Detail column on the Material 3 Expressive "Color Block" design.
 *
 * A plain [BookDetailTopBar] is hoisted above the scroll; the [LazyColumn] then carries, in order:
 * offline + scan advisories, the centered [CompactHero], a centered [StatsRow], the grouped
 * frameless [AboutSection] (description + Genres + Tags), the connected [PrimaryActionsSection],
 * the frameless [DetailsSection], readers, and chapters.
 */
@Suppress("LongParameterList", "LongMethod")
@Composable
private fun ImmersiveBookDetail(
    bookId: String,
    state: BookDetailUiState.Ready,
    documents: List<BookDocument> = emptyList(),
    onOpenDocument: (docId: String) -> Unit = {},
    downloadStatus: BookDownloadStatus,
    isComplete: Boolean,
    hasProgress: Boolean,
    isAdmin: Boolean,
    isWaitingForWifi: Boolean,
    showPlaybackActions: Boolean,
    onBackClick: () -> Unit,
    onEditClick: () -> Unit,
    onFindMetadataClick: () -> Unit,
    onMarkCompleteClick: () -> Unit,
    onMarkNotStartedClick: () -> Unit,
    onAddToShelfClick: () -> Unit,
    onAddToCollectionClick: () -> Unit,
    onShareClick: () -> Unit,
    onDeleteBookClick: () -> Unit,
    onPlayClick: () -> Unit,
    canPlay: Boolean,
    canDownload: Boolean,
    showServerWarning: Boolean,
    onRetryConnection: () -> Unit,
    onPlayDisabledClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onCancelClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onSeriesClick: (seriesId: String) -> Unit,
    onContributorClick: (contributorId: String) -> Unit,
    onTagClick: (tagId: String, tagName: String) -> Unit,
    onMoodClick: (moodId: String, moodName: String) -> Unit,
    onUserProfileClick: (userId: String) -> Unit,
    onSeeAllReaders: (bookId: String) -> Unit,
) {
    var isDescriptionExpanded by rememberSaveable { mutableStateOf(false) }
    var isChaptersExpanded by rememberSaveable { mutableStateOf(false) }
    // Full-cast overlay target — set by a folded hero author/narrator line, cleared on dismiss.
    var castRole by remember { mutableStateOf<CastRole?>(null) }

    val book = state.book
    val screenPadding = Modifier.padding(horizontal = Spacing.screenMargin)
    // Hero classification: the most-specific genre (deepest in the hierarchy) as a chip beside the
    // Abridged/Unabridged flag.
    val heroGenre = book.mostSpecificGenre()?.name

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        BookDetailTopBar(
            title = book.title,
            isComplete = isComplete,
            hasProgress = hasProgress,
            isAdmin = isAdmin,
            onBackClick = onBackClick,
            onEditClick = onEditClick,
            onFindMetadataClick = onFindMetadataClick,
            onMarkCompleteClick = onMarkCompleteClick,
            onMarkNotStartedClick = onMarkNotStartedClick,
            onAddToShelfClick = onAddToShelfClick,
            onAddToCollectionClick = onAddToCollectionClick,
            onShareClick = onShareClick,
            onDeleteClick = onDeleteBookClick,
        )

        val playerInset = LocalNowPlayingInsets.current.asPaddingValues().calculateBottomPadding()
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp + playerInset),
        ) {
            // Offline advisory — streaming unavailable, downloads still play.
            if (showServerWarning) {
                item {
                    OfflineBanner(
                        onRetryClick = onRetryConnection,
                        compact = true,
                        modifier = screenPadding.padding(vertical = 8.dp),
                    )
                }
            }

            // Scan-warning advisory — heads-up when the scanner flagged this book's files.
            item {
                BookDetailScanWarning(
                    hasScanWarning = state.hasScanWarning,
                    modifier = screenPadding.padding(vertical = 8.dp),
                )
            }

            // Identity — centered cover, title, subtitle, series chips, author, narrator.
            item {
                CompactHero(
                    coverPath = book.coverPath,
                    coverHash = book.coverHash,
                    bookId = bookId,
                    title = book.title,
                    genre = heroGenre,
                    abridged = book.abridged,
                    subtitle = state.subtitle,
                    series = book.series,
                    authors = book.authors,
                    narrators = book.narrators,
                    onContributorClick = onContributorClick,
                    onSeriesClick = onSeriesClick,
                    onShowCast = { castRole = it },
                    progress = state.progress,
                    timeRemaining = state.timeRemainingFormatted,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            // Stats — rating, duration, year, date added (centered).
            item {
                StatsRow(
                    rating = state.rating,
                    duration = book.duration,
                    year = state.year,
                    addedAt = state.addedAt,
                    modifier = screenPadding.padding(top = 16.dp),
                )
            }

            // Primary actions — connected Play + Download group, kept above the description so the
            // primary action is reachable without scrolling past the synopsis.
            if (showPlaybackActions) {
                item {
                    PrimaryActionsSection(
                        downloadStatus = downloadStatus,
                        onPlayClick = onPlayClick,
                        onDownloadClick = onDownloadClick,
                        onCancelClick = onCancelClick,
                        onDeleteClick = onDeleteClick,
                        modifier = screenPadding.padding(top = 20.dp),
                        isWaitingForWifi = isWaitingForWifi,
                        playEnabled = canPlay,
                        downloadEnabled = canDownload,
                        onPlayDisabledClick = onPlayDisabledClick,
                        showServerWarning = showServerWarning,
                    )
                }
            }

            // About — description + Genres + Tags, frameless.
            item {
                AboutSection(
                    description = state.descriptionText,
                    genres = state.genresList,
                    tags = state.tags,
                    moods = state.moods,
                    isLoadingTags = state.isLoadingTags,
                    isCard = false,
                    isDescriptionExpanded = isDescriptionExpanded,
                    onToggleDescriptionExpanded = { isDescriptionExpanded = !isDescriptionExpanded },
                    onGenreClick = null,
                    onTagClick = { tag -> onTagClick(tag.id, tag.displayName()) },
                    onMoodClick = { mood -> onMoodClick(mood.id, mood.displayName()) },
                    creditsSlot = null,
                    modifier = screenPadding.padding(top = 24.dp),
                )
            }

            // Readers — social reading activity.
            item {
                BookReadersSection(
                    bookId = bookId,
                    onUserClick = onUserProfileClick,
                    onSeeAllClick = onSeeAllReaders,
                    modifier = screenPadding.padding(vertical = 8.dp),
                )
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
                            onClick = { isChaptersExpanded = true },
                            shape = RoundedCornerShape(24.dp),
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
                        onOpenDocument = onOpenDocument,
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
                    onContributorClick = onContributorClick,
                    modifier = screenPadding.padding(top = 8.dp),
                )
            }
        }

        castRole?.let { role ->
            FullCastSheetFor(
                role = role,
                authors = book.authors,
                narrators = book.narrators,
                onContributorClick = onContributorClick,
                onDismiss = { castRole = null },
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
        else -> 0L
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
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Warning,
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
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
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
                        .clip(RoundedCornerShape(12.dp))
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
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
