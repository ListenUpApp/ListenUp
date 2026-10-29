package com.calypsan.listenup.client.features.library.components

import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.aspectRatio
import com.calypsan.listenup.client.design.theme.ContentShapes
import com.calypsan.listenup.client.presentation.library.LibraryStatusStrip
import listenup.composeapp.generated.resources.library_arriving_placeholder_a11y
import listenup.composeapp.generated.resources.library_book_menu_long_press
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.calypsan.listenup.client.design.components.AlphabetScrollbar
import com.calypsan.listenup.client.design.components.ListenUpButton
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import com.calypsan.listenup.client.design.components.cookieScallopShape
import com.calypsan.listenup.client.design.components.toCoverModel
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.ScanProgressState
import com.calypsan.listenup.client.domain.model.SyncState
import com.calypsan.listenup.client.features.library.BookCard
import com.calypsan.listenup.client.presentation.library.BookCardStatus
import com.calypsan.listenup.client.presentation.library.BookStatusCounts
import com.calypsan.listenup.client.presentation.library.BookStatusFilter
import com.calypsan.listenup.client.presentation.library.LetterSection
import com.calypsan.listenup.client.presentation.library.SortCategory
import com.calypsan.listenup.client.presentation.library.SortState
import com.calypsan.listenup.core.BookId
import kotlinx.coroutines.launch
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_no_items_yet
import listenup.composeapp.generated.resources.common_retry
import listenup.composeapp.generated.resources.error_unknown
import listenup.composeapp.generated.resources.library_add_audiobooks_to_your_server
import listenup.composeapp.generated.resources.library_failed_to_load_library
import listenup.composeapp.generated.resources.library_loading_your_library
import listenup.composeapp.generated.resources.library_your_audiobooks_will_appear_here
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.shape.CircleShape

private const val SCAN_PROGRESS_WIDTH_FRACTION = 0.6f
private const val INBOX_ENTRY_KEY = "library-inbox-entry"
private const val ARRIVING_PLACEHOLDERS = 4
private const val ARRIVING_PLACEHOLDER_TAG = "library-arriving-placeholder"
private val PlaceholderLines = listOf(0.9f, 0.6f)

/**
 * Section header: an Expressive coral "cookie" badge carrying the section [letter], trailed by a
 * rounded divider line (matches the design's scalloped section letters). Shared by the Books grid
 * and the Authors and Narrators lists. A heading, so TalkBack can move from letter to letter.
 */
@Composable
internal fun SectionHeader(
    letter: Char,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) { heading() }
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
                    .clip(CircleShape)
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
 * @param bookLetters The shared letter sections over [books]; empty for a sort with no letters
 * @param bookStatus What each book's card says about where the reader is with it (spec §2.6)
 * @param progressUnderTitle Compact grid: the progress mark sits under the title, not on the art
 * @param isInSelectionMode Whether multi-select mode is active
 * @param selectedBookIds Set of currently selected book IDs
 * @param onCategorySelected Called when the user selects a new sort category
 * @param onDirectionToggle Called when the user toggles sort direction
 * @param onToggleIgnoreArticles Called when the "Ignore articles" toggle is tapped (article-aware sorting)
 * @param onBookClick Callback when a book is clicked (navigates or toggles selection)
 * @param bookMenu A book's menu, opened by a long press or right-click on its cover (spec §3.1.2); Select is one tap
 *   inside it. Null = no menu. While selecting there is none: a tap already toggles.
 * @param onRetry Callback when retry is clicked in error state
 * @param isFilteredEmpty The library has books but [statusFilter] matches none of them
 * @param statusFilter The active reading-state filter, which picks the filtered-empty copy
 * @param onShowAllBooks Clears the status filter from the filtered-empty state
 * @param header Content drawn as the grid's first, full-width item — the Library's inbox entry. Scrolls
 *   with the books.
 * @param statusStrip What the pinned status strip above the grid says. While the library is still arriving the grid
 *   ends in placeholders, and an empty grid is never called empty.
 * @param showSortControl Whether the sort sits above the grid; false where a header band already carries it (the
 *   Android tablet)
 * @param statusCounts Whole-library counts per reading state: the Sort row leads with the books shown and its menu
 *   offers the status as a "Show" group. Null leaves the plain sort.
 * @param onStatusSelected Invoked when a reading state is chosen from the menu
 * @param modifier Optional modifier
 */
@Suppress("LongParameterList")
@Composable
internal fun BooksContent(
    books: List<BookListItem>,
    hasLoadedBooks: Boolean,
    syncState: SyncState,
    isServerScanning: Boolean,
    scanProgress: ScanProgressState? = null,
    sortState: SortState,
    ignoreTitleArticles: Boolean,
    bookLetters: List<LetterSection> = emptyList(),
    bookStatus: Map<BookId, BookCardStatus> = emptyMap(),
    progressUnderTitle: Boolean = false,
    isInSelectionMode: Boolean = false,
    selectedBookIds: Set<String> = emptySet(),
    onCategorySelected: (SortCategory) -> Unit,
    onDirectionToggle: () -> Unit,
    onToggleIgnoreArticles: () -> Unit,
    onBookClick: (String) -> Unit,
    bookMenu: LibraryBookMenuHost? = null,
    onRetry: () -> Unit,
    isFilteredEmpty: Boolean = false,
    statusFilter: BookStatusFilter = BookStatusFilter.ALL,
    onShowAllBooks: () -> Unit = {},
    header: (@Composable () -> Unit)? = null,
    statusStrip: LibraryStatusStrip = LibraryStatusStrip.Hidden,
    showSortControl: Boolean = true,
    statusCounts: BookStatusCounts? = null,
    onStatusSelected: (BookStatusFilter) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val isArriving = statusStrip is LibraryStatusStrip.Arriving
    // The Sort row leads with the books shown and carries the status (board `Lib-Android-After`). The trigger stills
    // while books are picked: filtering mid-selection would change what is being picked.
    val sortRow: @Composable () -> Unit = {
        val shown = statusCounts?.countFor(statusFilter)
        LibrarySortCard(
            state = sortState,
            categories = SortCategory.booksCategories,
            count = shown,
            unit = if (shown == 1) "book" else "books",
            ignoreArticles = ignoreTitleArticles,
            showArticleToggle = sortState.category == SortCategory.TITLE,
            onCategorySelected = onCategorySelected,
            onDirectionToggle = onDirectionToggle,
            onToggleArticles = onToggleIgnoreArticles,
            visible = true,
            modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
            status = statusCounts?.let { LibraryStatusChoice(statusFilter, it, onStatusSelected) },
            enabled = !isInSelectionMode,
        )
    }
    Box(modifier = modifier.fillMaxSize()) {
        when {
            // Haven't loaded from database yet - show loading
            !hasLoadedBooks -> {
                BooksLoadingState()
            }

            // Empty while the library is still arriving (a first sync, or this device's first catch-up): say so
            // over placeholders. Never "empty": the socket is Connected for the whole seed, so the sync state
            // cannot tell this apart from a library with nothing in it.
            books.isEmpty() && !isFilteredEmpty && isArriving -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    PinnedStatusStrip(statusStrip)
                    BookGrid(
                        books = books,
                        bookLetters = emptyList(),
                        bookStatus = bookStatus,
                        progressUnderTitle = progressUnderTitle,
                        statusFilter = statusFilter,
                        isInSelectionMode = isInSelectionMode,
                        selectedBookIds = selectedBookIds,
                        onBookClick = onBookClick,
                        bookMenu = bookMenu,
                        header = header,
                        trailingPlaceholders = ARRIVING_PLACEHOLDERS,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // Loaded but empty AND syncing - show loading
            books.isEmpty() && syncState is SyncState.Syncing -> {
                BooksLoadingState()
            }

            // The library has books, but the status filter matches none: say so, and offer the way
            // back. Ahead of the error and scanning states, which are about the library being empty.
            isFilteredEmpty -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    header?.let { slot ->
                        Box(modifier = Modifier.padding(horizontal = Spacing.gridMargin, vertical = 12.dp)) { slot() }
                    }
                    // The menu that chose the filter stays, so another status is one tap away, not only Show all.
                    if (showSortControl) sortRow()
                    FilteredEmptyState(
                        filter = statusFilter,
                        onShowAll = onShowAllBooks,
                        modifier = Modifier.weight(1f),
                    )
                }
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

            // Loaded AND truly empty - show empty state, under the header if there is one: an admin
            // whose every new book is held has an empty grid, and the inbox entry is the way in.
            books.isEmpty() -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    header?.let { slot ->
                        Box(modifier = Modifier.padding(horizontal = Spacing.gridMargin, vertical = 12.dp)) { slot() }
                    }
                    Box(modifier = Modifier.weight(1f)) { BooksEmptyState() }
                }
            }

            // Loaded with books - show grid
            else -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    PinnedStatusStrip(statusStrip)
                    // The Android tablet draws the sort in its second header band instead.
                    if (showSortControl) sortRow()
                    BookGrid(
                        books = books,
                        bookLetters = bookLetters,
                        bookStatus = bookStatus,
                        progressUnderTitle = progressUnderTitle,
                        statusFilter = statusFilter,
                        isInSelectionMode = isInSelectionMode,
                        selectedBookIds = selectedBookIds,
                        onBookClick = onBookClick,
                        bookMenu = bookMenu,
                        header = header,
                        trailingPlaceholders = if (isArriving) ARRIVING_PLACEHOLDERS else 0,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** The status strip, pinned above the grid at the grid's margins; nothing when there is nothing to say. */
@Composable
private fun PinnedStatusStrip(strip: LibraryStatusStrip) {
    if (strip is LibraryStatusStrip.Hidden) return
    LibraryStatusStripSlot(
        strip = strip,
        modifier =
            Modifier.padding(
                start = Spacing.gridMargin,
                end = Spacing.gridMargin,
                top = Spacing.xs,
                bottom = Spacing.sm,
            ),
    )
}

/**
 * A book card's shape with nothing in it yet: a cover-sized block and two lines. The first is named for TalkBack ("More
 * books arriving"); the rest are hidden, so the reader hears the news once rather than four times.
 */
@Composable
private fun ArrivingPlaceholderCard(
    isNamed: Boolean,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(Res.string.library_arriving_placeholder_a11y)
    Column(
        modifier =
            modifier
                .testTag(ARRIVING_PLACEHOLDER_TAG)
                .clearAndSetSemantics { if (isNamed) contentDescription = label else hideFromAccessibility() },
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest, ContentShapes.card),
        )
        PlaceholderLines.forEach { fraction ->
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(Spacing.md)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, ContentShapes.card),
            )
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
    bookLetters: List<LetterSection>,
    bookStatus: Map<BookId, BookCardStatus>,
    progressUnderTitle: Boolean,
    statusFilter: BookStatusFilter,
    isInSelectionMode: Boolean,
    selectedBookIds: Set<String>,
    onBookClick: (String) -> Unit,
    bookMenu: LibraryBookMenuHost?,
    header: (@Composable () -> Unit)?,
    trailingPlaceholders: Int,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    // The one book whose menu is open, by id, so it survives rotation and a recycled cell.
    var menuFor by rememberSaveable { mutableStateOf<String?>(null) }
    val longPressLabel = stringResource(Res.string.library_book_menu_long_press)
    val menu = bookMenu.takeUnless { isInSelectionMode }

    // A new status filter is a new list: open it at the top. A grid keeps its place by item key, so
    // without this a filter opened part-way down, its first books scrolled off above. The last filter
    // is saved so returning from Book Detail (same filter) keeps the reader's place.
    var lastStatusFilter by rememberSaveable { mutableStateOf(statusFilter) }
    LaunchedEffect(statusFilter) {
        if (statusFilter != lastStatusFilter) {
            lastStatusFilter = statusFilter
            gridState.scrollToItem(0)
        }
    }

    // Headers and the scrollbar read the shared letter sections (empty for a sort with no letters).
    val gridItems = remember(books, bookLetters) { bookGridItems(books, bookLetters) }

    val hasHeader = header != null
    // The header slot (the inbox strip) can arrive after the books, as a first sync settles. The grid keeps its first
    // book anchored when an item is inserted above it, which would leave the strip just above the viewport. A reader
    // who was at the top stays at the top, so the strip is seen.
    LaunchedEffect(hasHeader) {
        if (hasHeader && gridState.firstVisibleItemIndex == 1 && gridState.firstVisibleItemScrollOffset == 0) {
            gridState.scrollToItem(0)
        }
    }
    val alphabetIndex =
        remember(bookLetters, hasHeader) {
            libraryAlphabetIndex(bookLetters, withHeaders = true, leadingItems = if (hasHeader) 1 else 0)
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
                    start = Spacing.gridMargin,
                    end = Spacing.gridMargin,
                    top = 12.dp,
                    bottom = 16.dp,
                    // While selecting, the grid's end scrolls clear of the floating selection toolbar.
                ).plusSelectionToolbar(isInSelectionMode),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (header != null) {
                item(key = INBOX_ENTRY_KEY, span = { GridItemSpan(maxLineSpan) }, contentType = "header-slot") {
                    header()
                }
            }
            items(
                items = gridItems,
                key = { gridItem -> gridItem.key },
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
                            narrators = gridItem.book.narratorNames.ifBlank { null },
                            libraryStatus = bookStatus[gridItem.book.id],
                            progressUnderTitle = progressUnderTitle,
                            hasDocuments = gridItem.book.hasDocuments,
                            isInSelectionMode = isInSelectionMode,
                            isSelected = bookId in selectedBookIds,
                            onLongPress =
                                menu?.let { host ->
                                    {
                                        scope.launch {
                                            // Board: the menu hangs below the cover. Make room first, so it only
                                            // flips up when the grid cannot scroll any further.
                                            val menuHeight =
                                                estimatedMenuHeight(host.actionsFor(bookId)).value * density.density
                                            gridState.makeRoomBelowCover(gridItem.key, menuHeight)
                                            menuFor = bookId
                                        }
                                    }
                                },
                            longPressLabel = menu?.let { longPressLabel },
                            coverOverlay =
                                menu?.takeIf { menuFor == bookId }?.let { host ->
                                    {
                                        LibraryBookMenu(
                                            expanded = true,
                                            onDismiss = { menuFor = null },
                                            primary = host.primaryFor(bookId),
                                            actions = host.actionsFor(bookId),
                                        )
                                    }
                                },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
            items(
                count = trailingPlaceholders,
                key = { "arriving-placeholder-$it" },
                contentType = { "placeholder" },
            ) { index ->
                ArrivingPlaceholderCard(isNamed = index == 0)
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
            scanProgress?.progressFraction?.let { fraction ->
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(SCAN_PROGRESS_WIDTH_FRACTION),
                )
            }
            scanProgress?.changesSummary?.let { changesSummary ->
                Text(
                    text = changesSummary,
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

/**
 * Scrolls the grid just enough that [menuHeight] px fits between the foot of [key]'s cover (a square as wide as its
 * cell) and the bottom of the viewport, never lifting the card's top out of view.
 */
private suspend fun LazyGridState.makeRoomBelowCover(
    key: Any,
    menuHeight: Float,
) {
    val info = layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return
    val coverBottom = info.offset.y + info.size.width
    val room = layoutInfo.viewportEndOffset - coverBottom
    val shortfall = menuHeight - room
    val headroom = (info.offset.y - layoutInfo.viewportStartOffset).toFloat()
    val scroll = minOf(shortfall, headroom)
    if (scroll > 0f) animateScrollBy(scroll)
}
