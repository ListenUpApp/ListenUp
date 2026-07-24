package com.calypsan.listenup.client.features.library.components

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.AlphabetIndex
import com.calypsan.listenup.client.design.components.AlphabetScrollbar
import com.calypsan.listenup.client.design.components.ListenUpButton
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicatorSmall
import com.calypsan.listenup.client.design.components.cookieScallopShape
import com.calypsan.listenup.client.design.components.toCoverModel
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.ScanProgressState
import com.calypsan.listenup.client.domain.model.SyncState
import com.calypsan.listenup.client.features.library.BookCard
import com.calypsan.listenup.client.presentation.library.SortCategory
import com.calypsan.listenup.client.presentation.library.SortState
import com.calypsan.listenup.client.util.sortLetter
import com.calypsan.listenup.core.BookId
import kotlinx.coroutines.launch
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_no_items_yet
import listenup.composeapp.generated.resources.common_retry
import listenup.composeapp.generated.resources.error_unknown
import listenup.composeapp.generated.resources.library_add_audiobooks_to_your_server
import listenup.composeapp.generated.resources.library_failed_to_load_library
import listenup.composeapp.generated.resources.library_loading_your_library
import listenup.composeapp.generated.resources.library_summary
import listenup.composeapp.generated.resources.library_your_audiobooks_will_appear_here
import org.jetbrains.compose.resources.stringResource

private const val SCAN_PROGRESS_WIDTH_FRACTION = 0.6f

/**
 * Represents an item in the book grid - either a section header or a book.
 */
private sealed class BookGridItem {
    /** Section divider showing the [letter] heading above the books that follow it. */
    data class Header(
        val letter: Char,
    ) : BookGridItem()

    /** A single book row in the grid. */
    data class BookItem(
        val book: BookListItem,
    ) : BookGridItem()
}

/**
 * Groups books with section headers based on the current sort category.
 * Only adds headers for text-based sorts (Title, Author, Series).
 *
 * @param ignoreArticles When true and sorting by title, uses article-aware
 *                       sorting (A, An, The ignored), affecting which letter
 *                       each book groups under.
 */
private fun groupBooksWithHeaders(
    books: List<BookListItem>,
    sortState: SortState,
    ignoreArticles: Boolean,
): List<BookGridItem> {
    // For numeric/date sorts, no headers
    val isTextSort =
        sortState.category in
            listOf(
                SortCategory.TITLE,
                SortCategory.AUTHOR,
                SortCategory.SERIES,
            )
    if (!isTextSort) {
        return books.map { BookGridItem.BookItem(it) }
    }

    var currentLetter: Char? = null

    return buildList {
        for (book in books) {
            val letter =
                when (sortState.category) {
                    // Title sort uses article-aware letter extraction
                    SortCategory.TITLE -> {
                        book.title.sortLetter(ignoreArticles)
                    }

                    // Other text sorts use literal first letter
                    SortCategory.AUTHOR -> {
                        val first = book.authorNames.firstOrNull()?.uppercaseChar() ?: '#'
                        if (first.isLetter()) first else '#'
                    }

                    SortCategory.SERIES -> {
                        val first = book.seriesName?.firstOrNull()?.uppercaseChar() ?: '#'
                        if (first.isLetter()) first else '#'
                    }

                    else -> {
                        '#'
                    }
                }

            if (letter != currentLetter) {
                add(BookGridItem.Header(letter))
                currentLetter = letter
            }
            add(BookGridItem.BookItem(book))
        }
    }
}

/**
 * Section header: an Expressive coral "cookie" badge carrying the section [letter], trailed by a
 * rounded divider line (matches the design's scalloped section letters).
 */
@Composable
private fun SectionHeader(
    letter: Char,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(46.dp)
                    .clip(cookieScallopShape())
                    .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = letter.toString(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .height(3.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        )
    }
}

/**
 * Content for the Books tab in the Library screen.
 *
 * @param books List of books to display
 * @param hasLoadedBooks Whether initial database load has completed (distinguishes loading vs empty)
 * @param syncState Current sync status for loading/error states
 * @param isServerScanning Whether the server is currently scanning the library
 * @param sortState Current sort state (category + direction)
 * @param ignoreTitleArticles Whether to ignore articles (A, An, The) when sorting by title
 * @param bookProgress Map of bookId to progress (0.0-1.0) for in-progress books
 * @param bookIsFinished Map of bookId to isFinished flag (authoritative completion status from server)
 * @param isInSelectionMode Whether multi-select mode is active
 * @param selectedBookIds Set of currently selected book IDs
 * @param onCategorySelected Called when the user selects a new sort category
 * @param onDirectionToggle Called when the user toggles sort direction
 * @param onToggleIgnoreArticles Called when the "Ignore articles" toggle is tapped (article-aware sorting)
 * @param onBookClick Callback when a book is clicked (navigates or toggles selection)
 * @param onBookLongPress Callback when a book is long-pressed (enters selection mode)
 * @param onRetry Callback when retry is clicked in error state
 * @param modifier Optional modifier
 */
@Suppress("LongParameterList")
@Composable
fun BooksContent(
    books: List<BookListItem>,
    hasLoadedBooks: Boolean,
    syncState: SyncState,
    isServerScanning: Boolean,
    scanProgress: ScanProgressState? = null,
    sortState: SortState,
    ignoreTitleArticles: Boolean,
    bookProgress: Map<BookId, Float>,
    bookIsFinished: Map<BookId, Boolean> = emptyMap(),
    isInSelectionMode: Boolean = false,
    selectedBookIds: Set<String> = emptySet(),
    onCategorySelected: (SortCategory) -> Unit,
    onDirectionToggle: () -> Unit,
    onToggleIgnoreArticles: () -> Unit,
    onBookClick: (String) -> Unit,
    onBookLongPress: ((String) -> Unit)? = null,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        when {
            // Haven't loaded from database yet - show loading
            !hasLoadedBooks -> {
                BooksLoadingState()
            }

            // Loaded but empty AND syncing - show loading
            books.isEmpty() && syncState is SyncState.Syncing -> {
                BooksLoadingState()
            }

            // Loaded but empty AND sync error - show error
            books.isEmpty() && syncState is SyncState.Error -> {
                BooksErrorState(
                    error = syncState.exception ?: Exception(syncState.message),
                    onRetry = onRetry,
                )
            }

            // Loaded but empty AND server is scanning - show scanning state
            books.isEmpty() && isServerScanning -> {
                BooksScanningState(scanProgress = scanProgress)
            }

            // Loaded AND truly empty - show empty state
            books.isEmpty() -> {
                BooksEmptyState()
            }

            // Loaded with books - show grid
            else -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    if (isServerScanning && scanProgress != null) {
                        ScanProgressBanner(scanProgress = scanProgress)
                    }
                    LibrarySortCard(
                        state = sortState,
                        categories = SortCategory.booksCategories,
                        count = books.size,
                        unit = "titles",
                        ignoreArticles = ignoreTitleArticles,
                        showArticleToggle = sortState.category == SortCategory.TITLE,
                        onCategorySelected = onCategorySelected,
                        onDirectionToggle = onDirectionToggle,
                        onToggleArticles = onToggleIgnoreArticles,
                        visible = true,
                        modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
                    )
                    BookGrid(
                        books = books,
                        sortState = sortState,
                        ignoreTitleArticles = ignoreTitleArticles,
                        bookProgress = bookProgress,
                        bookIsFinished = bookIsFinished,
                        isInSelectionMode = isInSelectionMode,
                        selectedBookIds = selectedBookIds,
                        onBookClick = onBookClick,
                        onBookLongPress = onBookLongPress,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * Grid of book cards with sort split button and alphabet scrollbar.
 */
@Suppress("LongMethod", "CognitiveComplexMethod", "LongParameterList")
@Composable
private fun BookGrid(
    books: List<BookListItem>,
    sortState: SortState,
    ignoreTitleArticles: Boolean,
    bookProgress: Map<BookId, Float>,
    bookIsFinished: Map<BookId, Boolean>,
    isInSelectionMode: Boolean,
    selectedBookIds: Set<String>,
    onBookClick: (String) -> Unit,
    onBookLongPress: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    // Group books with section headers for text-based sorts
    val gridItems =
        remember(books, sortState, ignoreTitleArticles) {
            groupBooksWithHeaders(books, sortState, ignoreTitleArticles)
        }

    // Build alphabet index based on current sort category, accounting for headers
    val alphabetIndex =
        remember(gridItems, sortState) {
            when (sortState.category) {
                SortCategory.TITLE, SortCategory.AUTHOR, SortCategory.SERIES -> {
                    // Map letters to their header positions in the grid
                    val letterPositions = mutableMapOf<Char, Int>()
                    gridItems.forEachIndexed { index, item ->
                        if (item is BookGridItem.Header) {
                            letterPositions[item.letter] = index
                        }
                    }
                    if (letterPositions.isNotEmpty()) {
                        // Sort: non-letters (#) first, then alphabetically
                        val letters =
                            letterPositions.keys
                                .sortedWith(compareBy({ it.isLetter() }, { it }))
                        AlphabetIndex(letters, letterPositions)
                    } else {
                        null
                    }
                }

                else -> {
                    null
                } // Numeric sorts don't benefit from alphabet navigation
            }
        }

    val isScrolling by remember {
        derivedStateOf { gridState.isScrollInProgress }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = 160.dp),
            contentPadding =
                PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 12.dp,
                    bottom = 16.dp,
                ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(
                items = gridItems,
                key = { gridItem ->
                    when (gridItem) {
                        is BookGridItem.Header -> "header-${gridItem.letter}"
                        is BookGridItem.BookItem -> gridItem.book.id.value
                    }
                },
                // Distinct content types so Compose reuses the right slot on scroll instead of
                // re-creating layouts when a header and a book swap positions.
                contentType = { gridItem ->
                    when (gridItem) {
                        is BookGridItem.Header -> "header"
                        is BookGridItem.BookItem -> "book"
                    }
                },
                span = { gridItem ->
                    when (gridItem) {
                        is BookGridItem.Header -> GridItemSpan(maxLineSpan)
                        is BookGridItem.BookItem -> GridItemSpan(1)
                    }
                },
            ) { gridItem ->
                when (gridItem) {
                    is BookGridItem.Header -> {
                        SectionHeader(letter = gridItem.letter)
                    }

                    is BookGridItem.BookItem -> {
                        val bookId = gridItem.book.id.value
                        BookCard(
                            cover = gridItem.book.toCoverModel(),
                            onClick = { onBookClick(bookId) },
                            duration = gridItem.book.formatDuration(),
                            progress = bookProgress[gridItem.book.id],
                            isFinished = bookIsFinished[gridItem.book.id] ?: false,
                            hasDocuments = gridItem.book.hasDocuments,
                            isInSelectionMode = isInSelectionMode,
                            isSelected = bookId in selectedBookIds,
                            onLongPress =
                                onBookLongPress?.let { callback ->
                                    { callback(bookId) }
                                },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }

        // Alphabet scrollbar (only for text-based sorts), anchored to the content's top-end.
        if (alphabetIndex != null) {
            AlphabetScrollbar(
                alphabetIndex = alphabetIndex,
                onLetterSelected = { index ->
                    // Instant scroll - animateScrollToItem causes jank on large lists
                    // as it composes/disposes hundreds of items during animation.
                    // Haptic feedback + scrollbar animations provide sufficient feedback.
                    scope.launch { gridState.scrollToItem(index) }
                },
                isScrolling = isScrolling,
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 12.dp, end = 4.dp, bottom = 0.dp),
            )
        }
    }
}

@Composable
private fun BooksLoadingState() {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainerLow),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ListenUpLoadingIndicator()
            Text(
                text = stringResource(Res.string.library_loading_your_library),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Compact banner showing scan progress when books are already loaded.
 */
@Composable
private fun ScanProgressBanner(scanProgress: ScanProgressState) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ListenUpLoadingIndicatorSmall()
                Text(
                    text =
                        scanProgress.phaseDisplayName +
                            if (scanProgress.total > 0) " ${scanProgress.current}/${scanProgress.total}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                val summary = scanProgress.changesSummary
                if (summary != null) {
                    Text(
                        text = stringResource(Res.string.library_summary, summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                    )
                }
            }
            if (scanProgress.progressFraction != null) {
                LinearProgressIndicator(
                    progress = { scanProgress.progressFraction!! },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    trackColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.12f),
                )
            }
        }
    }
}

@Composable
private fun BooksScanningState(scanProgress: ScanProgressState? = null) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainerLow),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            ListenUpLoadingIndicator()
            Text(
                text =
                    if (scanProgress != null) {
                        scanProgress.phaseDisplayName +
                            if (scanProgress.total > 0) " ${scanProgress.current}/${scanProgress.total}" else ""
                    } else {
                        "Scanning your library..."
                    },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (scanProgress?.progressFraction != null) {
                LinearProgressIndicator(
                    progress = { scanProgress.progressFraction!! },
                    modifier = Modifier.fillMaxWidth(SCAN_PROGRESS_WIDTH_FRACTION),
                )
            }
            if (scanProgress?.changesSummary != null) {
                Text(
                    text = scanProgress.changesSummary!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(Res.string.library_your_audiobooks_will_appear_here),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun BooksEmptyState() {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainerLow),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.LibraryBooks,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(Res.string.common_no_items_yet, "audiobooks"),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(Res.string.library_add_audiobooks_to_your_server),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun BooksErrorState(
    error: Exception,
    onRetry: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainerLow),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.error,
            )
            Text(
                text = stringResource(Res.string.library_failed_to_load_library),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                text = error.message ?: stringResource(Res.string.error_unknown),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(8.dp))
            ListenUpButton(text = stringResource(Res.string.common_retry), onClick = onRetry)
        }
    }
}
