package com.calypsan.listenup.client.features.library

import com.calypsan.listenup.client.features.shell.LandingEffect
import com.calypsan.listenup.client.features.shell.ShellLanding
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
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import com.calypsan.listenup.client.design.components.ListenUpButton
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.util.PlatformBackHandler
import com.calypsan.listenup.client.domain.model.SyncState
import com.calypsan.listenup.client.features.library.components.AuthorsContent
import com.calypsan.listenup.client.features.library.components.BookSelectionScaffold
import com.calypsan.listenup.client.features.library.components.BooksContent
import com.calypsan.listenup.client.features.library.components.LibraryBookMenuActions
import com.calypsan.listenup.client.features.library.components.LibraryBookMenuHost
import com.calypsan.listenup.client.features.library.components.LibraryInboxStrip
import com.calypsan.listenup.client.features.library.components.rememberBookSelectionScaffoldState
import com.calypsan.listenup.client.presentation.library.bookMenuPrimary
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.features.library.components.LibrarySectionGroup
import com.calypsan.listenup.client.features.library.components.SelectAllAction
import com.calypsan.listenup.client.features.library.components.NarratorsContent
import com.calypsan.listenup.client.features.library.components.SeriesContent
import com.calypsan.listenup.client.features.shell.ShellDestination
import com.calypsan.listenup.client.features.shell.components.AppHeaderSlot
import com.calypsan.listenup.client.presentation.admin.InboxBadgeViewModel
import com.calypsan.listenup.client.presentation.books.BookMultiSelectViewModel
import com.calypsan.listenup.client.presentation.books.SelectionMode
import com.calypsan.listenup.client.presentation.library.BookStatusFilter
import com.calypsan.listenup.client.presentation.library.LibraryUiEvent
import com.calypsan.listenup.client.presentation.library.LibraryUiState
import com.calypsan.listenup.client.presentation.library.LibraryViewModel
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
 * @param onEditSelected Navigate to the bulk metadata editor with the current multi-selection,
 *   with a callback that ends the selection once an apply has landed
 *   (null = this host has no route to the editor, so the action is not offered)
 * @param onPlayBook Plays a book from its menu: the same play entry Home and Book Detail use
 * @param onShareBook Shares a book from its menu, given its id and title (null = no share here, so no item)
 * @param onEditBook Opens a book's metadata editor from its menu's admin group (null = no route, so no item)
 * @param onOpenInbox Opens the admin inbox from the Books view's entry
 *   (null = this host has no route to the inbox, so the entry is not offered)
 * @param modifier Modifier from parent (includes scaffold padding)
 * @param viewModel The LibraryViewModel (injected via Koin)
 * @param multiSelect The per-screen multi-select ViewModel (injected via Koin)
 * @param inboxBadge The held count behind the inbox strip (injected via Koin)
 */
@Composable
fun LibraryScreen(
    onBookClick: (String) -> Unit,
    onSeriesClick: (String) -> Unit,
    onAuthorClick: (String) -> Unit,
    onNarratorClick: (String) -> Unit,
    appHeader: AppHeaderSlot,
    onEditSelected: ((List<String>, endSelection: () -> Unit) -> Unit)? = null,
    onPlayBook: (String) -> Unit = {},
    onShareBook: ((bookId: String, title: String) -> Unit)? = null,
    onEditBook: ((String) -> Unit)? = null,
    onOpenInbox: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = koinViewModel(),
    multiSelect: BookMultiSelectViewModel = koinViewModel(),
    inboxBadge: InboxBadgeViewModel = koinViewModel(),
) {
    // Trigger intelligent auto-sync when screen becomes visible (only once)
    LaunchedEffect(Unit) {
        viewModel.onScreenVisible()
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Shell-scoped like the nav badge: the same ViewModelStore resolves the same InboxBadgeViewModel.
    val heldCount by inboxBadge.heldCount.collectAsStateWithLifecycle()
    val previewBookIds by inboxBadge.previewBookIds.collectAsStateWithLifecycle()

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
                multiSelect = multiSelect,
                onBookClick = onBookClick,
                onSeriesClick = onSeriesClick,
                onAuthorClick = onAuthorClick,
                onNarratorClick = onNarratorClick,
                appHeader = appHeader,
                onEditSelected = onEditSelected,
                onPlayBook = onPlayBook,
                onShareBook = onShareBook,
                onEditBook = onEditBook,
                heldCount = heldCount,
                previewBookIds = previewBookIds,
                onOpenInbox = onOpenInbox,
                onEvent = viewModel::onEvent,
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
internal fun LibraryLoadedContent(
    state: LibraryUiState.Loaded,
    multiSelect: BookMultiSelectViewModel,
    onBookClick: (String) -> Unit,
    onSeriesClick: (String) -> Unit,
    onAuthorClick: (String) -> Unit,
    onNarratorClick: (String) -> Unit,
    appHeader: AppHeaderSlot,
    onEditSelected: ((List<String>, endSelection: () -> Unit) -> Unit)?,
    heldCount: Int,
    onOpenInbox: (() -> Unit)?,
    onEvent: (LibraryUiEvent) -> Unit,
    modifier: Modifier = Modifier,
    onPlayBook: (String) -> Unit = {},
    onShareBook: ((bookId: String, title: String) -> Unit)? = null,
    onEditBook: ((String) -> Unit)? = null,
    previewBookIds: List<String> = emptyList(),
) {
    val haptics = LocalHaptics.current
    val selectionState = rememberBookSelectionScaffoldState()
    val isAdmin by multiSelect.adminAccess.collectAsStateWithLifecycle()
    val selectionMode by multiSelect.selectionMode.collectAsStateWithLifecycle()
    val isInSelectionMode = selectionMode is SelectionMode.Active
    val selectedBookIds = (selectionMode as? SelectionMode.Active)?.selectedIds.orEmpty()

    // Handle back press to exit selection mode
    PlatformBackHandler(enabled = isInSelectionMode) {
        multiSelect.exitSelectionMode()
    }

    var selectedSection by rememberSaveable(stateSaver = LibrarySectionSaver) { mutableStateOf(LibrarySection.Books) }
    LandingEffect(ShellLanding.LibraryNewestFirst) {
        selectedSection = LibrarySection.Books
        onEvent(LibraryUiEvent.BooksShownNewestFirst)
    }
    LandingEffect(ShellLanding.LibraryInProgress) {
        selectedSection = LibrarySection.Books
        onEvent(LibraryUiEvent.StatusFilterChanged(BookStatusFilter.IN_PROGRESS))
    }
    val isWide =
        currentWindowAdaptiveInfoV2().windowSizeClass.isWidthAtLeastBreakpoint(
            WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND,
        )
    // The tablet layout (spec §3.1.3, ≥ 840dp): two header bands. Medium widths keep the phone header.
    val isExpanded =
        currentWindowAdaptiveInfoV2().windowSizeClass.isWidthAtLeastBreakpoint(
            WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND,
        )

    // Long press or right-click on a Library cover opens its menu (Simon's M3E call; Library only, open question 1:
    // Home, Discover and shelves keep long press = Select). Select is one tap inside it.
    val bookMenu =
        LibraryBookMenuHost(
            primaryFor = { id -> bookMenuPrimary(state.bookStatus[BookId(id)]) },
            actionsFor = { id ->
                LibraryBookMenuActions(
                    onPrimary = { onPlayBook(id) },
                    onAddToShelf = selectionState.shelveOneOrNull(multiSelect)?.let { shelve -> { shelve(id) } },
                    onShare =
                        onShareBook?.let { share ->
                            { share(id, state.books.firstOrNull { it.id.value == id }?.title.orEmpty()) }
                        },
                    onSelect = { multiSelect.enterSelectionMode(id) },
                    onAddToCollection =
                        if (isAdmin) selectionState.collectOneOrNull(multiSelect)?.let { collect -> { collect(id) } } else null,
                    onEditDetails = if (isAdmin) onEditBook?.let { edit -> { edit(id) } } else null,
                )
            },
        )

    val booksGrid: @Composable () -> Unit = {
        BooksContent(
            books = state.books,
            hasLoadedBooks = true,
            syncState = state.syncState,
            isServerScanning = state.isServerScanning,
            scanProgress = state.scanProgress,
            sortState = state.booksSortState,
            ignoreTitleArticles = state.ignoreTitleArticles,
            bookLetters = state.bookLetters,
            bookStatus = state.bookStatus,
            progressUnderTitle = !isWide,
            isInSelectionMode = isInSelectionMode,
            selectedBookIds = selectedBookIds,
            onCategorySelected = { onEvent(LibraryUiEvent.BooksCategoryChanged(it)) },
            onDirectionToggle = { onEvent(LibraryUiEvent.BooksDirectionToggled) },
            onToggleIgnoreArticles = { onEvent(LibraryUiEvent.ToggleIgnoreTitleArticles) },
            onBookClick = { bookId ->
                if (isInSelectionMode) multiSelect.toggleSelection(bookId) else onBookClick(bookId)
            },
            bookMenu = bookMenu,
            onRetry = { onEvent(LibraryUiEvent.RefreshRequested) },
            isFilteredEmpty = state.isFilteredEmpty,
            statusFilter = state.statusFilter,
            onShowAllBooks = { onEvent(LibraryUiEvent.StatusFilterChanged(BookStatusFilter.ALL)) },
            statusCounts = state.statusCounts,
            onStatusSelected = { onEvent(LibraryUiEvent.StatusFilterChanged(it)) },
            statusStrip = state.statusStrip,
            showSortControl = !isExpanded,
            // Books view only (canvas): Series, Authors and Narrators do not carry it,
            // and selecting books turns the grid into a picking surface the entry would clutter. A host
            // with no route to the inbox gets no entry: a tile that opens nothing reads as broken.
            // The tablet carries the inbox as a pill in its first header band instead.
            header =
                if (heldCount > 0 && !isInSelectionMode && onOpenInbox != null && !isExpanded) {
                    {
                        LibraryInboxStrip(heldCount = heldCount, onOpenInbox = onOpenInbox)
                    }
                } else {
                    null
                },
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Custom shell header (search/sync/avatar). The Library title is a big Expressive hero.
            if (isExpanded) {
                // The shell's trailing actions only: the first band carries the title.
                appHeader {}
                LibraryTabletHeader(
                    state = state,
                    selectedSection = selectedSection,
                    onSelectSection = { selectedSection = it },
                    heldCount = heldCount,
                    previewBookIds = previewBookIds,
                    onOpenInbox = onOpenInbox,
                    onEvent = onEvent,
                    isInSelectionMode = isInSelectionMode,
                )
            } else {
                appHeader {
                    Text(
                        text = ShellDestination.Library.title,
                        style = if (isWide) MaterialTheme.typography.displayMedium else MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                // The four sections as one connected group. Wider chrome gets more air below the big header.
                LibrarySectionGroup(
                    selected = selectedSection,
                    onSelect = { selectedSection = it },
                    modifier = Modifier.padding(top = if (isWide) 20.dp else 8.dp, bottom = 6.dp),
                )
            }

            // Pull-to-refresh wraps the active filter's content (syncs all data).
            PullToRefreshBox(
                isRefreshing = state.syncState is SyncState.Syncing,
                onRefresh = {
                    haptics.thresholdActivate()
                    onEvent(LibraryUiEvent.RefreshRequested)
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                when (selectedSection) {
                    LibrarySection.Books -> booksGrid()
                    LibrarySection.Series ->
                        SeriesContent(
                            series = state.series,
                            seriesProgress = state.seriesProgress,
                            sortState = state.seriesSortState,
                            letters = state.seriesLetters,
                            ignoreArticles = state.ignoreTitleArticles,
                            onCategorySelected = { onEvent(LibraryUiEvent.SeriesCategoryChanged(it)) },
                            onDirectionToggle = { onEvent(LibraryUiEvent.SeriesDirectionToggled) },
                            onToggleIgnoreArticles = { onEvent(LibraryUiEvent.ToggleIgnoreTitleArticles) },
                            onSeriesClick = onSeriesClick,
                        )

                    LibrarySection.Authors ->
                        AuthorsContent(
                            authors = state.authors,
                            sortState = state.authorsSortState,
                            letters = state.authorLetters,
                            onCategorySelected = { onEvent(LibraryUiEvent.AuthorsCategoryChanged(it)) },
                            onDirectionToggle = { onEvent(LibraryUiEvent.AuthorsDirectionToggled) },
                            onAuthorClick = onAuthorClick,
                            showCovers = isExpanded,
                        )

                    LibrarySection.Narrators ->
                        NarratorsContent(
                            narrators = state.narrators,
                            sortState = state.narratorsSortState,
                            letters = state.narratorLetters,
                            onCategorySelected = { onEvent(LibraryUiEvent.NarratorsCategoryChanged(it)) },
                            onDirectionToggle = { onEvent(LibraryUiEvent.NarratorsDirectionToggled) },
                            onNarratorClick = onNarratorClick,
                            showCovers = isExpanded,
                        )
                }
            }
        }

        // Multi-select overlay: top toolbar, picker sheets, and success feedback.
        BookSelectionScaffold(
            multiSelect = multiSelect,
            onEditSelected = onEditSelected,
            state = selectionState,
            // Every book the Books grid shows, as filtered (open question 8). The other sections pick no books.
            selectAll =
                if (selectedSection == LibrarySection.Books && state.books.isNotEmpty()) {
                    SelectAllAction(
                        count = state.books.size,
                        isAllSelected = state.books.all { it.id.value in selectedBookIds },
                        onSelectAll = { multiSelect.selectAll(state.books.map { it.id.value }) },
                        // Clears the picks and stays in selection (enterSelectionMode with none picked).
                        onDeselectAll = { multiSelect.enterSelectionMode() },
                    )
                } else {
                    null
                },
        )
    }
}
