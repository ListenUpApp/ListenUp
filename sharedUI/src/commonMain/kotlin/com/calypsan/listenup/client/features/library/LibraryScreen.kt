package com.calypsan.listenup.client.features.library

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
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.util.PlatformBackHandler
import com.calypsan.listenup.client.domain.model.SyncState
import com.calypsan.listenup.client.features.library.components.AuthorsContent
import com.calypsan.listenup.client.features.library.components.BookSelectionScaffold
import com.calypsan.listenup.client.features.library.components.BooksContent
import com.calypsan.listenup.client.features.library.components.LibraryFilterChips
import com.calypsan.listenup.client.features.library.components.NarratorsContent
import com.calypsan.listenup.client.features.library.components.SeriesContent
import com.calypsan.listenup.client.features.shell.ShellDestination
import com.calypsan.listenup.client.features.shell.components.AppHeaderSlot
import com.calypsan.listenup.client.presentation.books.BookMultiSelectViewModel
import com.calypsan.listenup.client.presentation.books.SelectionMode
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
 * @param modifier Modifier from parent (includes scaffold padding)
 * @param viewModel The LibraryViewModel (injected via Koin)
 * @param multiSelect The per-screen multi-select ViewModel (injected via Koin)
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
    multiSelect: BookMultiSelectViewModel = koinViewModel(),
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
                multiSelect = multiSelect,
                onBookClick = onBookClick,
                onSeriesClick = onSeriesClick,
                onAuthorClick = onAuthorClick,
                onNarratorClick = onNarratorClick,
                appHeader = appHeader,
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
private fun LibraryLoadedContent(
    state: LibraryUiState.Loaded,
    multiSelect: BookMultiSelectViewModel,
    onBookClick: (String) -> Unit,
    onSeriesClick: (String) -> Unit,
    onAuthorClick: (String) -> Unit,
    onNarratorClick: (String) -> Unit,
    appHeader: AppHeaderSlot,
    onEvent: (LibraryUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHaptics.current
    val selectionMode by multiSelect.selectionMode.collectAsStateWithLifecycle()
    val isInSelectionMode = selectionMode is SelectionMode.Active
    val selectedBookIds = (selectionMode as? SelectionMode.Active)?.selectedIds.orEmpty()

    // Handle back press to exit selection mode
    PlatformBackHandler(enabled = isInSelectionMode) {
        multiSelect.exitSelectionMode()
    }

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
            onCategorySelected = { onEvent(LibraryUiEvent.BooksCategoryChanged(it)) },
            onDirectionToggle = { onEvent(LibraryUiEvent.BooksDirectionToggled) },
            onToggleIgnoreArticles = { onEvent(LibraryUiEvent.ToggleIgnoreTitleArticles) },
            onBookClick = { bookId ->
                if (isInSelectionMode) multiSelect.toggleSelection(bookId) else onBookClick(bookId)
            },
            onBookLongPress = multiSelect::enterSelectionMode,
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
                onRefresh = {
                    haptics.thresholdActivate()
                    onEvent(LibraryUiEvent.RefreshRequested)
                },
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

        // Multi-select overlay: top toolbar, picker sheets, and success feedback.
        BookSelectionScaffold(multiSelect = multiSelect)
    }
}
