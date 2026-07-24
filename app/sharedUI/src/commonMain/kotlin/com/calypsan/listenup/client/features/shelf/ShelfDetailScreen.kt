package com.calypsan.listenup.client.features.shelf

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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import com.calypsan.listenup.client.design.components.ListenUpScaffold
import com.calypsan.listenup.client.design.components.cookieScallopShape
import com.calypsan.listenup.client.design.components.toCoverModel
import com.calypsan.listenup.client.domain.model.ShelfBook
import com.calypsan.listenup.client.domain.model.ShelfDetail
import com.calypsan.listenup.client.features.library.BookCard
import com.calypsan.listenup.client.presentation.shelf.ShelfBookSort
import com.calypsan.listenup.client.presentation.shelf.ShelfDetailUiState
import com.calypsan.listenup.client.presentation.shelf.ShelfDetailViewModel
import com.calypsan.listenup.client.presentation.shelf.ShelfGridWidth
import com.calypsan.listenup.client.presentation.shelf.sortShelfBooks
import kotlin.time.Duration.Companion.seconds
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_about
import listenup.composeapp.generated.resources.common_back
import listenup.composeapp.generated.resources.common_no_items_yet
import listenup.composeapp.generated.resources.common_private
import listenup.composeapp.generated.resources.common_read_less
import listenup.composeapp.generated.resources.common_read_more
import listenup.composeapp.generated.resources.shelf_add_books_from_the_library
import listenup.composeapp.generated.resources.shelf_books_in_shelf
import listenup.composeapp.generated.resources.shelf_edit_shelf
import listenup.composeapp.generated.resources.shelf_title_fallback
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * Screen displaying a shelf's details and its books.
 *
 * A color-blocked hero (scalloped shelf badge + Books/Total stat chips) sits above a
 * responsive square-cover grid. Pushed full-screen route: keeps a back affordance and an
 * owner-only Edit action; no navigation rail.
 *
 * @param shelfId The ID of the shelf to display.
 * @param onBack Callback when the back button is clicked.
 * @param onBookClick Callback when a book cover is clicked.
 * @param onEditClick Owner-only edit callback (navigates to shelf edit).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShelfDetailScreen(
    shelfId: String,
    onBack: () -> Unit,
    onBookClick: (String) -> Unit,
    onEditClick: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
    viewModel: ShelfDetailViewModel = koinViewModel(),
) {
    LaunchedEffect(shelfId) {
        viewModel.loadShelf(shelfId)
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    val readyState = state as? ShelfDetailUiState.Ready
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.snackbarMessages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    ListenUpScaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = readyState?.detail?.name ?: stringResource(Res.string.shelf_title_fallback),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.common_back),
                        )
                    }
                },
                actions = {
                    if (readyState?.isOwner == true && onEditClick != null) {
                        IconButton(onClick = { onEditClick(shelfId) }) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = stringResource(Res.string.shelf_edit_shelf),
                            )
                        }
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
            )
        },
        modifier = modifier,
    ) { paddingValues ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
        ) {
            when (val current = state) {
                is ShelfDetailUiState.Loading, ShelfDetailUiState.Idle -> {
                    ListenUpLoadingIndicator(modifier = Modifier.align(Alignment.Center))
                }

                is ShelfDetailUiState.Error -> {
                    Text(
                        text = current.message,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }

                is ShelfDetailUiState.Ready -> {
                    ShelfDetailContent(
                        detail = current.detail,
                        isOwner = current.isOwner,
                        onBookClick = onBookClick,
                    )
                }
            }
        }
    }
}

/** Description length beyond which the expandable "Read more" toggle is shown. */
private const val DESCRIPTION_EXPAND_THRESHOLD = 150

/**
 * Ready-state content: an adaptive cover grid with full-span hero, optional description,
 * and a "Books in shelf" header.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShelfDetailContent(
    detail: ShelfDetail,
    isOwner: Boolean,
    onBookClick: (String) -> Unit,
) {
    var isDescriptionExpanded by rememberSaveable { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf(ShelfBookSort.ADDED_NEWEST) }
    val sortedBooks = sortShelfBooks(detail.books, sort)

    val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass
    val gridWidth =
        when {
            windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND) -> {
                ShelfGridWidth.Expanded
            }

            windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND) -> {
                ShelfGridWidth.Medium
            }

            else -> {
                ShelfGridWidth.Compact
            }
        }
    val isWide = gridWidth != ShelfGridWidth.Compact

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 160.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            ShelfHero(
                detail = detail,
                isWide = isWide,
                totalDuration = DurationFormatter.hoursMinutes(detail.totalDurationSeconds.seconds),
            )
        }

        detail.description?.takeIf { it.isNotBlank() }?.let { description ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                ShelfDescription(
                    description = description,
                    isExpanded = isDescriptionExpanded,
                    onToggle = { isDescriptionExpanded = !isDescriptionExpanded },
                )
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            ShelfBooksHeader(
                sort = sort,
                onSortChange = { sort = it },
                showSortPill = detail.books.isNotEmpty(),
            )
        }

        if (detail.books.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                ShelfEmptyState(isOwner = isOwner)
            }
        } else {
            items(items = sortedBooks, key = { it.id.value }) { book ->
                ShelfBookGridItem(book = book, onClick = { onBookClick(book.id.value) })
            }
        }
    }
}

/** Color-blocked hero: scalloped shelf badge, name, and Books/Total stat chips. */
@Composable
private fun ShelfHero(
    detail: ShelfDetail,
    isWide: Boolean,
    totalDuration: String,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
    ) {
        // Decorative brand "blob" bleeding off the top-right corner.
        Box(
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 60.dp, y = (-50).dp)
                    .size(200.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
        )

        if (isWide) {
            Row(
                modifier = Modifier.padding(32.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                ShelfBadge()
                Column(modifier = Modifier.weight(1f)) {
                    ShelfHeroTexts(detail = detail, totalDuration = totalDuration, center = false)
                }
            }
        } else {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ShelfBadge()
                Spacer(Modifier.height(16.dp))
                ShelfHeroTexts(detail = detail, totalDuration = totalDuration, center = true)
            }
        }
    }
}

/** The scalloped (cookie) shelf badge with a library glyph. */
@Composable
private fun ShelfBadge() {
    Box(
        modifier =
            Modifier
                .size(110.dp)
                .clip(cookieScallopShape())
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.LibraryBooks,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(46.dp),
        )
    }
}

/** Private indicator + title + stat chips, aligned for compact (centered) or wide (start). */
@Composable
private fun ShelfHeroTexts(
    detail: ShelfDetail,
    totalDuration: String,
    center: Boolean,
) {
    Column(
        horizontalAlignment = if (center) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        if (detail.isPrivate) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = stringResource(Res.string.common_private),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                )
            }
            Spacer(Modifier.height(6.dp))
        }

        Text(
            text = detail.name,
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            textAlign = if (center) TextAlign.Center else TextAlign.Start,
        )

        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ShelfStatChip(
                icon = Icons.AutoMirrored.Filled.LibraryBooks,
                value = "${detail.bookCount}",
                label = if (detail.bookCount == 1) "Book" else "Books",
            )
            ShelfStatChip(
                icon = Icons.Default.Schedule,
                value = totalDuration,
                label = "Total",
            )
        }
    }
}

/** A filled stat pill: icon + bold value + muted label. */
@Composable
private fun ShelfStatChip(
    icon: ImageVector,
    value: String,
    label: String,
) {
    Row(
        modifier =
            Modifier
                .height(46.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(start = 14.dp, end = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "Books in shelf" title on the left; sort pill on the right (hidden when the shelf is empty). */
@Composable
private fun ShelfBooksHeader(
    sort: ShelfBookSort,
    onSortChange: (ShelfBookSort) -> Unit,
    showSortPill: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(Res.string.shelf_books_in_shelf),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (showSortPill) {
            ShelfSortPill(sort = sort, onSortChange = onSortChange)
        }
    }
}

/** Pill showing the current [ShelfBookSort]; tap to pick another from a dropdown. */
@Composable
private fun ShelfSortPill(
    sort: ShelfBookSort,
    onSortChange: (ShelfBookSort) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(
            onClick = { expanded = true },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Row(
                modifier = Modifier.padding(start = 14.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Sort,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = sort.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ShelfBookSort.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        onSortChange(option)
                        expanded = false
                    },
                    trailingIcon =
                        if (option == sort) {
                            { Icon(Icons.Default.Check, contentDescription = null) }
                        } else {
                            null
                        },
                )
            }
        }
    }
}

/** A single book cell: the canonical library [BookCard] filling the grid column. */
@Composable
private fun ShelfBookGridItem(
    book: ShelfBook,
    onClick: () -> Unit,
) {
    BookCard(
        cover = book.toCoverModel(),
        onClick = onClick,
    )
}

/** Empty-state block shown when the shelf has no books. */
@Composable
private fun ShelfEmptyState(isOwner: Boolean) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 40.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier =
                Modifier
                    .size(104.dp)
                    .clip(cookieScallopShape())
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Book,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(46.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(Res.string.common_no_items_yet, "books"),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (isOwner) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(Res.string.shelf_add_books_from_the_library),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Expandable "About" description block. */
@Composable
private fun ShelfDescription(
    description: String,
    isExpanded: Boolean,
    onToggle: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(Res.string.common_about),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (isExpanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (description.length > DESCRIPTION_EXPAND_THRESHOLD) {
            TextButton(
                onClick = onToggle,
                contentPadding = PaddingValues(0.dp),
            ) {
                Text(stringResource(if (isExpanded) Res.string.common_read_less else Res.string.common_read_more))
            }
        }
    }
}
