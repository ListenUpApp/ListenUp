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
import com.calypsan.listenup.client.design.components.rememberCoverColors
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.client.domain.model.DownloadOutcome
import com.calypsan.listenup.client.features.library.ShelfPickerSheet
import com.calypsan.listenup.client.features.bookdetail.components.BookListeningHistorySection
import com.calypsan.listenup.client.features.bookdetail.components.BookReadersSection
import com.calypsan.listenup.client.features.bookdetail.components.ChapterListItem
import com.calypsan.listenup.client.features.bookdetail.components.ChaptersHeader
import com.calypsan.listenup.client.features.bookdetail.components.ContextMetadataSection
import com.calypsan.listenup.client.features.bookdetail.components.DescriptionSection
import com.calypsan.listenup.client.features.bookdetail.components.HeroSection
import com.calypsan.listenup.client.features.bookdetail.components.MarkCompleteDialog
import com.calypsan.listenup.client.features.bookdetail.components.PrimaryActionsSection
import com.calypsan.listenup.client.features.bookdetail.components.TalentSectionWithRoles
import com.calypsan.listenup.client.features.bookdetail.components.WideBookDetail
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailUiState
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailViewModel
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import com.calypsan.listenup.core.Success as ResultSuccess
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_scan_warning
import listenup.composeapp.generated.resources.book_detail_server_is_unreachable_connect_to

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
 * (dialogs, server reachability, download status) and delegates layout
 * to [BookDetailContent].
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

    val downloadStatusFlow = remember(bookId) { platformActions.observeBookStatus(BookId(bookId)) }
    val downloadStatus by downloadStatusFlow
        .collectAsStateWithLifecycle(initialValue = BookDownloadStatus.NotDownloaded(bookId))

    // WiFi-only download state detection
    val wifiOnlyFlow = remember { platformActions.observeWifiOnlyDownloads() }
    val wifiOnlyDownloads by wifiOnlyFlow.collectAsStateWithLifecycle(initialValue = false)
    val unmeteredFlow = remember { platformActions.observeIsOnUnmeteredNetwork() }
    val isOnUnmeteredNetwork by unmeteredFlow.collectAsStateWithLifecycle(initialValue = true)

    // Show "Waiting for WiFi" when:
    // - Download is queued AND
    // - WiFi-only is enabled AND
    // - Not currently on WiFi/unmetered network
    val isWaitingForWifi =
        downloadStatus.isQueued() &&
            wifiOnlyDownloads &&
            !isOnUnmeteredNetwork

    // Server reachability check - runs when screen loads
    var isServerReachable by remember { mutableStateOf<Boolean?>(null) }
    val isFullyDownloaded = downloadStatus is BookDownloadStatus.Completed
    LaunchedEffect(bookId, isFullyDownloaded) {
        // Only check if book isn't downloaded - downloaded books always play
        if (!isFullyDownloaded) {
            isServerReachable = platformActions.checkServerReachable()
        }
    }

    // Playback availability: can play if book is downloaded OR server is confirmed reachable
    // While check is in progress (null), assume playable to avoid flicker for fast connections
    val canPlay = isFullyDownloaded || isServerReachable != false

    // Downloads are always allowed — they queue locally and don't need the server to be reachable
    val canDownload = platformActions.isPlaybackAvailable

    // Show warning when server is unreachable and book isn't downloaded (informational only)
    val showServerWarning = isServerReachable == false && !isFullyDownloaded

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
        downloadStatus = downloadStatus,
        isComplete = state.isComplete,
        hasProgress = hasProgress,
        isAdmin = state.isAdmin,
        isWaitingForWifi = isWaitingForWifi,
        showPlaybackActions = platformActions.isPlaybackAvailable,
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
                if (result is ResultSuccess) {
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
        canPlay = canPlay,
        canDownload = canDownload,
        showServerWarning = showServerWarning,
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
            downloadSize = downloadStatus.downloadedOrTotalBytes(),
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
 * Immersive book detail following audiobook user psychology.
 * Uses color extraction for dynamic, personalized theming.
 */
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

    // Use cached colors for instant rendering, fall back to runtime extraction
    val coverColors =
        rememberCoverColors(
            imagePath = book.coverPath,
            cachedDominantColor = book.dominantColor,
            cachedDarkMutedColor = book.darkMutedColor,
            cachedVibrantColor = book.vibrantColor,
        )

    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        // 1. HERO SECTION - Identity with color-extracted gradient
        item {
            HeroSection(
                coverPath = book.coverPath,
                bookId = bookId,
                title = book.title,
                subtitle = state.subtitle,
                progress = state.progress,
                timeRemaining = state.timeRemainingFormatted,
                coverColors = coverColors,
                isComplete = state.isComplete,
                hasProgress = hasProgress,
                isAdmin = state.isAdmin,
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
        }

        // Scan-warning advisory — heads-up when the scanner flagged this book's files.
        item {
            BookDetailScanWarning(
                hasScanWarning = state.hasScanWarning,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }

        // 2. THE TALENT - Who made this
        item {
            TalentSectionWithRoles(
                authors = book.authors,
                narrators = book.narrators,
                allContributors = book.allContributors,
                onContributorClick = onContributorClick,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }

        // 3. PRIMARY ACTIONS - What can I do (Thumb zone)
        if (showPlaybackActions) {
            item {
                PrimaryActionsSection(
                    downloadStatus = downloadStatus,
                    onPlayClick = onPlayClick,
                    onDownloadClick = onDownloadClick,
                    onCancelClick = onCancelClick,
                    onDeleteClick = onDeleteClick,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                    isWaitingForWifi = isWaitingForWifi,
                    playEnabled = canPlay,
                    downloadEnabled = canDownload,
                    onPlayDisabledClick = onPlayDisabledClick,
                )
            }

            // Server unreachable warning — informational only, does not disable buttons
            if (showServerWarning) {
                item {
                    ServerUnreachableBanner(
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
            }
        }

        // 4. CONTEXT METADATA - Series, Stats, Genres
        item {
            ContextMetadataSection(
                seriesId = book.seriesId,
                seriesName = state.series ?: book.seriesName,
                rating = state.rating,
                duration = book.duration,
                year = state.year,
                addedAt = state.addedAt,
                genres = state.genresList,
                onSeriesClick = onSeriesClick,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }

        // 5. THE HOOK - What's it about (Description)
        state.description.takeIf { it.isNotBlank() }?.let { description ->
            item {
                DescriptionSection(
                    description = description,
                    isExpanded = isDescriptionExpanded,
                    onToggleExpanded = { isDescriptionExpanded = !isDescriptionExpanded },
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
            }
        }

        // 6. TAGS - User categorization
        if (state.tags.isNotEmpty()) {
            item {
                TagsSection(
                    tags = state.tags,
                    isLoading = state.isLoadingTags,
                    onTagClick = { tag -> onTagClick(tag.id) },
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
        }

        // 7. READERS - Social reading activity
        item {
            BookReadersSection(
                bookId = bookId,
                onUserClick = onUserProfileClick,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }

        // 8. LISTENING HISTORY - Per-book session timeline (collapsible)
        item {
            BookListeningHistorySection(
                bookId = bookId,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }

        // 9. CHAPTERS - Deep dive
        item {
            Spacer(modifier = Modifier.height(16.dp))
            ChaptersHeader(
                chapterCount = state.chapters.size,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }

        val displayedChapters = if (isChaptersExpanded) state.chapters else state.chapters.take(5)
        itemsIndexed(
            items = displayedChapters,
            key = { _, chapter -> chapter.id },
        ) { index, chapter ->
            ChapterListItem(chapter = chapter, chapterNumber = index + 1)
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
    }
}

/** True when a download is in the queue but no file is actively transferring yet. */
private fun BookDownloadStatus.isQueued(): Boolean =
    this is BookDownloadStatus.InProgress && downloadingFiles == 0 && completedFiles == 0

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

@Composable
private fun ServerUnreachableBanner(modifier: Modifier = Modifier) {
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
                text = stringResource(Res.string.book_detail_server_is_unreachable_connect_to),
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
