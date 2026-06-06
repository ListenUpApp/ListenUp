package com.calypsan.listenup.client.features.bookdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.calypsan.listenup.client.design.components.LocalSnackbarHostState
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.DownloadOutcome
import com.calypsan.listenup.client.features.library.ShelfPickerSheet
import com.calypsan.listenup.client.features.bookdetail.components.AboutSection
import com.calypsan.listenup.client.features.bookdetail.components.BookDetailTopBar
import com.calypsan.listenup.client.features.bookdetail.components.BookListeningHistorySection
import com.calypsan.listenup.client.features.bookdetail.components.BookReadersSection
import com.calypsan.listenup.client.features.bookdetail.components.ChapterListItem
import com.calypsan.listenup.client.features.bookdetail.components.ChaptersHeader
import com.calypsan.listenup.client.features.bookdetail.components.CompactHero
import com.calypsan.listenup.client.features.bookdetail.components.CreditsSection
import com.calypsan.listenup.client.features.bookdetail.components.MarkCompleteDialog
import com.calypsan.listenup.client.features.bookdetail.components.OfflineBanner
import com.calypsan.listenup.client.features.bookdetail.components.PrimaryActionsSection
import com.calypsan.listenup.client.features.bookdetail.components.SeriesBadge
import com.calypsan.listenup.client.features.bookdetail.components.StatsRow
import com.calypsan.listenup.client.features.bookdetail.components.WideBookDetail
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailUiState
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailViewModel
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_narrated_by
import listenup.composeapp.generated.resources.book_detail_scan_warning

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
    onTagClick: (tagId: String) -> Unit,
    onUserProfileClick: (userId: String) -> Unit,
    viewModel: BookDetailViewModel = koinViewModel(),
) {
    LaunchedEffect(bookId) {
        viewModel.loadBook(bookId)
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
                            text = s.message,
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
                    onUserProfileClick = onUserProfileClick,
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
    onTagClick: (tagId: String) -> Unit,
    onUserProfileClick: (userId: String) -> Unit,
) {
    val platformActions: BookDetailPlatformActions = koinInject()
    val instanceRepository: InstanceRepository = koinInject()
    val scope = rememberCoroutineScope()
    val snackbarHostState = LocalSnackbarHostState.current

    var showDeleteDialog by remember { mutableStateOf(false) }
    var showMarkCompleteDialog by remember { mutableStateOf(false) }

    // Callback for opening metadata search
    val onFindMetadataClick: () -> Unit = {
        onMetadataSearchClick(bookId)
    }

    val book = state.book
    val hasProgress = state.progress != null

    BookDetailContent(
        bookId = bookId,
        state = state,
        downloadStatus = state.downloadStatus,
        isComplete = state.isComplete,
        hasProgress = hasProgress,
        isAdmin = state.isAdmin,
        isWaitingForWifi = state.isWaitingForWifi,
        showPlaybackActions = state.isPlaybackAvailable,
        onBackClick = onBackClick,
        onEditClick = { onEditClick(bookId) },
        onFindMetadataClick = onFindMetadataClick,
        onMarkCompleteClick = {
            if (state.isComplete) {
                viewModel.restartBook() // "Mark as Not Started" = restart
            } else {
                showMarkCompleteDialog = true
            }
        },
        onDiscardProgressClick = { viewModel.discardProgress() },
        onAddToShelfClick = { viewModel.showShelfPicker() },
        onAddToCollectionClick = { /* TODO: Implement */ },
        onShareClick = {
            scope.launch {
                val result = instanceRepository.getInstance()
                if (result is AppResult.Success) {
                    val instance = result.data
                    val baseUrl = (instance.remoteUrl ?: instance.localUrl)?.trimEnd('/') ?: return@launch
                    val url = "$baseUrl/share/book/${book.id.value}"
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

    state.shelfError?.let { error ->
        LaunchedEffect(error) {
            snackbarHostState.showSnackbar(error)
            viewModel.clearShelfError()
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
    onDiscardProgressClick: () -> Unit,
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
    onTagClick: (tagId: String) -> Unit,
    onUserProfileClick: (userId: String) -> Unit,
) {
    val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass

    // Use two-pane layout for medium+ width devices (tablets, foldables, desktop)
    val useTwoPane =
        windowSizeClass.isWidthAtLeastBreakpoint(
            WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND,
        )

    if (useTwoPane) {
        WideBookDetail(
            bookId = bookId,
            state = state,
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
            onDiscardProgressClick = onDiscardProgressClick,
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
            onUserProfileClick = onUserProfileClick,
        )
    } else {
        ImmersiveBookDetail(
            bookId = bookId,
            state = state,
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
            onDiscardProgressClick = onDiscardProgressClick,
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
            onUserProfileClick = onUserProfileClick,
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
 * the frameless [CreditsSection], readers, chapters, and per-book listening history.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongParameterList", "LongMethod")
@Composable
private fun ImmersiveBookDetail(
    bookId: String,
    state: BookDetailUiState.Ready,
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
    onDiscardProgressClick: () -> Unit,
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
    onTagClick: (tagId: String) -> Unit,
    onUserProfileClick: (userId: String) -> Unit,
) {
    var isDescriptionExpanded by rememberSaveable { mutableStateOf(false) }
    var isChaptersExpanded by rememberSaveable { mutableStateOf(false) }

    val book = state.book
    val screenPadding = Modifier.padding(horizontal = Spacing.screenMargin)
    val narratorLine =
        state.narrators.takeIf { it.isNotBlank() }?.let { names ->
            "${stringResource(Res.string.book_detail_narrated_by)} $names"
        }

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
            onDiscardProgressClick = onDiscardProgressClick,
            onAddToShelfClick = onAddToShelfClick,
            onAddToCollectionClick = onAddToCollectionClick,
            onShareClick = onShareClick,
            onDeleteClick = onDeleteBookClick,
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp),
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

            // Identity — centered cover, title, series, author, narrator.
            item {
                CompactHero(
                    coverPath = book.coverPath,
                    bookId = bookId,
                    title = book.title,
                    overline = null,
                    subtitle = state.subtitle,
                    authorLine = book.authorNames,
                    narratorLine = narratorLine,
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

            // Series badge — series also appears in the hero subtitle; this is the tappable jump-off.
            val seriesId = book.seriesId
            val seriesName = state.series ?: book.seriesName
            if (seriesId != null && seriesName != null) {
                item {
                    SeriesBadge(
                        seriesName = seriesName,
                        onClick = { onSeriesClick(seriesId) },
                        modifier = screenPadding.padding(top = 16.dp),
                    )
                }
            }

            // About — description + Genres + Tags, frameless.
            item {
                AboutSection(
                    description = state.description,
                    genres = state.genresList,
                    tags = state.tags,
                    isLoadingTags = state.isLoadingTags,
                    isCard = false,
                    isDescriptionExpanded = isDescriptionExpanded,
                    onToggleDescriptionExpanded = { isDescriptionExpanded = !isDescriptionExpanded },
                    onGenreClick = null,
                    onTagClick = { tag -> onTagClick(tag.id) },
                    creditsSlot = null,
                    modifier = screenPadding.padding(top = 24.dp),
                )
            }

            // Primary actions — connected Play + Download group.
            if (showPlaybackActions) {
                item {
                    PrimaryActionsSection(
                        downloadStatus = downloadStatus,
                        onPlayClick = onPlayClick,
                        onDownloadClick = onDownloadClick,
                        onCancelClick = onCancelClick,
                        onDeleteClick = onDeleteClick,
                        modifier = screenPadding.padding(vertical = 16.dp),
                        isWaitingForWifi = isWaitingForWifi,
                        playEnabled = canPlay,
                        downloadEnabled = canDownload,
                        onPlayDisabledClick = onPlayDisabledClick,
                        showServerWarning = showServerWarning,
                    )
                }
            }

            // Credits — every contributor role, frameless divided list.
            item {
                CreditsSection(
                    credits = book.allContributors,
                    grid = false,
                    onContributorClick = onContributorClick,
                    modifier = screenPadding.padding(top = 8.dp),
                )
            }

            // Readers — social reading activity.
            item {
                BookReadersSection(
                    bookId = bookId,
                    onUserClick = onUserProfileClick,
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
                            Text("Show all ${state.chapters.size} chapters")
                        }
                    }
                }
            }

            // Listening history — per-book session timeline, frameless and collapsible.
            item {
                BookListeningHistorySection(
                    bookId = bookId,
                    isCard = false,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
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

private suspend fun handleDownloadResult(
    result: AppResult<DownloadOutcome>,
    showSnackbar: suspend (String) -> Unit,
) {
    if (result is AppResult.Failure) {
        showSnackbar("Download failed: ${result.error.debugInfo ?: "Unknown error"}")
        return
    }
    val outcome = (result as AppResult.Success).data
    if (outcome is DownloadOutcome.InsufficientStorage) {
        val requiredMb = outcome.requiredBytes / 1_000_000
        val availableMb = outcome.availableBytes / 1_000_000
        showSnackbar("Not enough storage. Need ${requiredMb}MB, have ${availableMb}MB available.")
    }
    // DownloadOutcome.Started and DownloadOutcome.AlreadyDownloaded: no UI action needed
}
