package com.calypsan.listenup.client.features.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.design.components.ListenUpButton
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import com.calypsan.listenup.client.design.components.LocalSnackbarHostState
import com.calypsan.listenup.client.design.util.PlatformBackHandler
import com.calypsan.listenup.client.domain.model.SyncState
import com.calypsan.listenup.client.features.library.components.AuthorsContent
import com.calypsan.listenup.client.features.library.components.BooksContent
import com.calypsan.listenup.client.features.library.components.LibraryFilterChips
import com.calypsan.listenup.client.features.library.components.NarratorsContent
import com.calypsan.listenup.client.features.library.components.SelectionToolbar
import com.calypsan.listenup.client.features.library.components.SeriesContent
import com.calypsan.listenup.client.features.shell.ShellDestination
import com.calypsan.listenup.client.features.shell.components.AppHeaderSlot
import com.calypsan.listenup.client.presentation.library.LibraryActionEvent
import com.calypsan.listenup.client.presentation.library.LibraryActionsViewModel
import com.calypsan.listenup.client.presentation.library.LibraryUiEvent
import com.calypsan.listenup.client.presentation.library.LibraryUiState
import com.calypsan.listenup.client.presentation.library.LibraryViewModel
import com.calypsan.listenup.client.presentation.library.SelectionMode
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

/**
 * Library screen displaying the user's audiobook collection.
 *
 * Features:
 * - Four tabs: Books, Series, Authors, Narrators
 * - Swipeable content via HorizontalPager
 * - Pull-to-refresh for manual sync (applies to all tabs)
 * - Intelligent auto-sync on first visibility
 * - Split button sort controls (category + direction)
 *
 * This screen is designed to work within the AppShell scaffold,
 * so it does not include its own Scaffold or TopAppBar.
 *
 * @param onBookClick Callback when a book is clicked
 * @param onSeriesClick Callback when a series is clicked
 * @param onAuthorClick Callback when an author is clicked
 * @param onNarratorClick Callback when a narrator is clicked
 * @param appHeader The shell header slot, rendered above the filter chips
 * @param modifier Modifier from parent (includes scaffold padding)
 * @param viewModel The LibraryViewModel (injected via Koin)
 */
@Composable
fun LibraryScreen(
    onBookClick: (String) -> Unit,
    onSeriesClick: (String) -> Unit,
    onAuthorClick: (String) -> Unit,
    onNarratorClick: (String) -> Unit,
    appHeader: AppHeaderSlot,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = koinViewModel(),
    actionsViewModel: LibraryActionsViewModel = koinViewModel(),
) {
    // Trigger intelligent auto-sync when screen becomes visible (only once)
    LaunchedEffect(Unit) {
        viewModel.onScreenVisible()
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    when (val state = uiState) {
        is LibraryUiState.Loading -> {
            LibraryLoadingContent(modifier = modifier)
        }

        is LibraryUiState.Error -> {
            LibraryErrorContent(
                message = state.message,
                onRetry = { viewModel.onEvent(LibraryUiEvent.RefreshRequested) },
                modifier = modifier,
            )
        }

        is LibraryUiState.Loaded -> {
            LibraryLoadedContent(
                state = state,
                actionsViewModel = actionsViewModel,
                onBookClick = onBookClick,
                onSeriesClick = onSeriesClick,
                onAuthorClick = onAuthorClick,
                onNarratorClick = onNarratorClick,
                appHeader = appHeader,
                onEvent = viewModel::onEvent,
                onEnterSelectionMode = viewModel::enterSelectionMode,
                onToggleBookSelection = viewModel::toggleBookSelection,
                onExitSelectionMode = viewModel::exitSelectionMode,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun LibraryLoadingContent(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        ListenUpLoadingIndicator()
    }
}

@Composable
private fun LibraryErrorContent(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 8.dp),
            )
            ListenUpButton(
                text = "Retry",
                onClick = onRetry,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

@Suppress("LongMethod", "CognitiveComplexMethod", "LongParameterList")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryLoadedContent(
    state: LibraryUiState.Loaded,
    actionsViewModel: LibraryActionsViewModel,
    onBookClick: (String) -> Unit,
    onSeriesClick: (String) -> Unit,
    onAuthorClick: (String) -> Unit,
    onNarratorClick: (String) -> Unit,
    appHeader: AppHeaderSlot,
    onEvent: (LibraryUiEvent) -> Unit,
    onEnterSelectionMode: (String) -> Unit,
    onToggleBookSelection: (String) -> Unit,
    onExitSelectionMode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectionMode = state.selectionMode
    val isInSelectionMode = selectionMode is SelectionMode.Active
    val selectedBookIds = (selectionMode as? SelectionMode.Active)?.selectedIds ?: emptySet()

    // Action VM state
    val isAdmin by actionsViewModel.isAdmin.collectAsStateWithLifecycle()
    val collections by actionsViewModel.collections.collectAsStateWithLifecycle()
    val isAddingToCollection by actionsViewModel.isAddingToCollection.collectAsStateWithLifecycle()
    val myShelves by actionsViewModel.myShelves.collectAsStateWithLifecycle()
    val isAddingToShelf by actionsViewModel.isAddingToShelf.collectAsStateWithLifecycle()

    // Collection and shelf picker sheet state
    var showCollectionPicker by remember { mutableStateOf(false) }
    var showShelfPicker by remember { mutableStateOf(false) }

    // Handle back press to exit selection mode
    PlatformBackHandler(enabled = isInSelectionMode) {
        onExitSelectionMode()
    }

    // Notify actions VM when selection mode is entered (to refresh collections for admins)
    LaunchedEffect(selectionMode) {
        if (selectionMode is SelectionMode.Active) {
            actionsViewModel.onSelectionModeEntered()
        }
    }

    // Snackbar feedback for collection/shelf actions; dismisses the relevant picker on success.
    LibraryActionFeedback(
        actionsViewModel = actionsViewModel,
        onCollectionActionHandled = { showCollectionPicker = false },
        onShelfActionHandled = { showShelfPicker = false },
    )

    // "In progress" view: titles with partial (started-but-unfinished) playback.
    // Derived in the ViewModel's combine pipeline — no in-composition filter needed.
    val booksInProgress = state.booksInProgress

    var selectedFilter by rememberSaveable { mutableStateOf(LibraryFilter.Books) }
    val isWide =
        currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(
            WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND,
        )

    // The Books grid is reused for both the Books filter (all titles) and In progress (partial).
    val booksGrid: @Composable (List<BookListItem>) -> Unit = { books ->
        BooksContent(
            books = books,
            hasLoadedBooks = true,
            syncState = state.syncState,
            isServerScanning = state.isServerScanning,
            scanProgress = state.scanProgress,
            sortState = state.booksSortState,
            ignoreTitleArticles = state.ignoreTitleArticles,
            bookProgress = state.bookProgress,
            bookIsFinished = state.bookIsFinished,
            isInSelectionMode = isInSelectionMode,
            selectedBookIds = selectedBookIds,
            onToggleIgnoreArticles = { onEvent(LibraryUiEvent.ToggleIgnoreTitleArticles) },
            onBookClick = { bookId ->
                if (isInSelectionMode) onToggleBookSelection(bookId) else onBookClick(bookId)
            },
            onBookLongPress = { bookId -> onEnterSelectionMode(bookId) },
            onRetry = { onEvent(LibraryUiEvent.RefreshRequested) },
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Custom shell header (search/sync/avatar). The Library title is a big Expressive hero.
            appHeader {
                Text(
                    text = ShellDestination.Library.title,
                    style = if (isWide) MaterialTheme.typography.displayMedium else MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            // Filter chips replace the old tab row. Wider chrome gets more air below the big header.
            LibraryFilterChips(
                selected = selectedFilter,
                onSelect = { selectedFilter = it },
                modifier = Modifier.padding(top = if (isWide) 20.dp else 8.dp, bottom = 6.dp),
            )

            // Pull-to-refresh wraps the active filter's content (syncs all data).
            PullToRefreshBox(
                isRefreshing = state.syncState is SyncState.Syncing,
                onRefresh = { onEvent(LibraryUiEvent.RefreshRequested) },
                modifier = Modifier.fillMaxSize(),
            ) {
                when (selectedFilter) {
                    LibraryFilter.Books -> booksGrid(state.books)
                    LibraryFilter.InProgress -> booksGrid(booksInProgress)
                    LibraryFilter.Series ->
                        SeriesContent(
                            series = state.series,
                            sortState = state.seriesSortState,
                            ignoreArticles = state.ignoreTitleArticles,
                            onCategorySelected = { onEvent(LibraryUiEvent.SeriesCategoryChanged(it)) },
                            onDirectionToggle = { onEvent(LibraryUiEvent.SeriesDirectionToggled) },
                            onToggleIgnoreArticles = { onEvent(LibraryUiEvent.ToggleIgnoreTitleArticles) },
                            onSeriesClick = onSeriesClick,
                        )

                    LibraryFilter.Authors ->
                        AuthorsContent(
                            authors = state.authors,
                            sortState = state.authorsSortState,
                            onCategorySelected = { onEvent(LibraryUiEvent.AuthorsCategoryChanged(it)) },
                            onDirectionToggle = { onEvent(LibraryUiEvent.AuthorsDirectionToggled) },
                            onAuthorClick = onAuthorClick,
                        )

                    LibraryFilter.Narrators ->
                        NarratorsContent(
                            narrators = state.narrators,
                            sortState = state.narratorsSortState,
                            onCategorySelected = { onEvent(LibraryUiEvent.NarratorsCategoryChanged(it)) },
                            onDirectionToggle = { onEvent(LibraryUiEvent.NarratorsDirectionToggled) },
                            onNarratorClick = onNarratorClick,
                        )
                }
            }
        }

        // Selection toolbar (overlays at top)
        AnimatedVisibility(
            visible = isInSelectionMode,
            enter = slideInVertically { -it },
            exit = slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            SelectionToolbar(
                selectedCount = selectedBookIds.size,
                onAddToShelf = { showShelfPicker = true },
                onAddToCollection =
                    if (isAdmin) {
                        { showCollectionPicker = true }
                    } else {
                        null
                    },
                onClose = { onExitSelectionMode() },
            )
        }
    }

    // Collection picker sheet. Collections are admin-managed — gate the picker
    // presentation on isAdmin as defense-in-depth. The multi-select flow adds existing
    // collections only; inline create lives on the Book Detail picker (canCreate = false here).
    if (showCollectionPicker && isAdmin) {
        CollectionPickerSheet(
            collections = collections,
            selectedBookCount = selectedBookIds.size,
            onCollectionSelected = { collectionId ->
                actionsViewModel.addSelectedToCollection(collectionId)
            },
            onCreateAndAddToCollection = {},
            onDismiss = { showCollectionPicker = false },
            isLoading = isAddingToCollection,
        )
    }

    // Shelf picker sheet
    if (showShelfPicker) {
        ShelfPickerSheet(
            shelves = myShelves,
            selectedBookCount = selectedBookIds.size,
            onShelfSelected = { shelfId ->
                actionsViewModel.addSelectedToShelf(shelfId)
            },
            onCreateAndAddToShelf = { name ->
                actionsViewModel.createShelfAndAddBooks(name)
            },
            onDismiss = { showShelfPicker = false },
            isLoading = isAddingToShelf,
        )
    }
}

/**
 * Collects [LibraryActionsViewModel.events] and surfaces snackbar feedback for collection/shelf
 * actions. On a successful add it dismisses the matching picker via [onCollectionActionHandled] /
 * [onShelfActionHandled]. Snackbars are launched on a dedicated scope so they outlive the collect.
 */
@Composable
private fun LibraryActionFeedback(
    actionsViewModel: LibraryActionsViewModel,
    onCollectionActionHandled: () -> Unit,
    onShelfActionHandled: () -> Unit,
) {
    val snackbarHostState = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        actionsViewModel.events.collect { event ->
            when (event) {
                is LibraryActionEvent.BooksAddedToCollection -> {
                    onCollectionActionHandled()
                    val message =
                        if (event.count == 1) {
                            "1 book added to collection"
                        } else {
                            "${event.count} books added to collection"
                        }
                    scope.launch { snackbarHostState.showSnackbar(message) }
                }

                is LibraryActionEvent.AddToCollectionFailed -> {
                    scope.launch { snackbarHostState.showSnackbar("Failed to add: ${event.message}") }
                }

                is LibraryActionEvent.BooksAddedToShelf -> {
                    onShelfActionHandled()
                    val message =
                        if (event.count == 1) "1 book added to shelf" else "${event.count} books added to shelf"
                    scope.launch { snackbarHostState.showSnackbar(message) }
                }

                is LibraryActionEvent.ShelfCreatedAndBooksAdded -> {
                    onShelfActionHandled()
                    val bookText = if (event.bookCount == 1) "1 book" else "${event.bookCount} books"
                    scope.launch { snackbarHostState.showSnackbar("Created \"${event.shelfName}\" with $bookText") }
                }

                is LibraryActionEvent.AddToShelfFailed -> {
                    scope.launch { snackbarHostState.showSnackbar("Failed to add: ${event.message}") }
                }
            }
        }
    }
}
