package com.calypsan.listenup.client.features.readingorder

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import com.calypsan.listenup.client.design.components.BookCoverImage
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import com.calypsan.listenup.client.design.components.ListenUpScaffold
import com.calypsan.listenup.client.design.reorderable.ReorderMove
import com.calypsan.listenup.client.design.reorderable.ReorderNode
import com.calypsan.listenup.client.design.reorderable.ReorderableList
import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.domain.model.ReadingOrder
import com.calypsan.listenup.client.features.readingorder.components.OrderSpines
import com.calypsan.listenup.client.features.readingorder.components.PrivacyLabel
import com.calypsan.listenup.client.features.readingorder.components.booksCountText
import com.calypsan.listenup.client.presentation.readingorder.OrderBookRowUi
import com.calypsan.listenup.client.presentation.readingorder.ReadingOrderDetailEvent
import com.calypsan.listenup.client.presentation.readingorder.ReadingOrderDetailUiState
import com.calypsan.listenup.client.presentation.readingorder.ReadingOrderDetailViewModel
import com.calypsan.listenup.core.ReadingOrderId
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import kotlin.time.Duration.Companion.milliseconds
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_back
import listenup.composeapp.generated.resources.common_cancel
import listenup.composeapp.generated.resources.common_delete
import listenup.composeapp.generated.resources.common_not_found
import listenup.composeapp.generated.resources.reading_orders_active_row
import listenup.composeapp.generated.resources.reading_orders_add_books
import listenup.composeapp.generated.resources.reading_orders_books_in_order
import listenup.composeapp.generated.resources.reading_orders_delete_order
import listenup.composeapp.generated.resources.reading_orders_delete_order_body
import listenup.composeapp.generated.resources.reading_orders_delete_order_title
import listenup.composeapp.generated.resources.reading_orders_reorder
import listenup.composeapp.generated.resources.reading_orders_reorder_hint
import listenup.composeapp.generated.resources.reading_orders_remove_book
import listenup.composeapp.generated.resources.reading_orders_set_active
import listenup.composeapp.generated.resources.reading_orders_title

private val WIDE_HERO_WIDTH = 380.dp
private val HERO_SPINE_HEIGHT = 72.dp
private val ATTRIBUTION_MAX_WIDTH = 320.dp

/**
 * The reading-order detail screen — a single order's hero (spines, name, attribution, meta),
 * follow (active-order) state, and, for the owner, reorder/add/remove/delete over its member
 * books.
 */
@Composable
fun ReadingOrderDetailScreen(
    orderId: String,
    seriesId: String,
    onBack: () -> Unit,
    vm: ReadingOrderDetailViewModel = koinViewModel(),
) {
    LaunchedEffect(orderId, seriesId) { vm.load(ReadingOrderId(orderId), seriesId) }

    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                ReadingOrderDetailEvent.Deleted -> onBack()
            }
        }
    }

    ListenUpScaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0)) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            when (val current = state) {
                ReadingOrderDetailUiState.Idle, ReadingOrderDetailUiState.Loading -> {
                    ListenUpLoadingIndicator(modifier = Modifier.align(Alignment.Center))
                }

                ReadingOrderDetailUiState.NotFound -> {
                    OrderNotFoundContent(onBack = onBack)
                }

                is ReadingOrderDetailUiState.Ready -> {
                    ReadingOrderDetailReadyContent(
                        current = current,
                        onBack = onBack,
                        onSetActive = vm::setActive,
                        onClearActive = vm::clearActive,
                        onReorder = vm::reorder,
                        onAddBooks = vm::addBooks,
                        onRemoveBook = vm::removeBook,
                        onDeleteOrder = vm::deleteOrder,
                    )
                }
            }
        }
    }
}

/**
 * The Ready-state body: adaptive narrow/wide content plus the delete confirm dialog and the
 * hosted add-books sheet. Extracted so dialog/sheet/reorder-mode state only exist while Ready.
 */
@Composable
private fun ReadingOrderDetailReadyContent(
    current: ReadingOrderDetailUiState.Ready,
    onBack: () -> Unit,
    onSetActive: () -> Unit,
    onClearActive: () -> Unit,
    onReorder: (List<String>) -> Unit,
    onAddBooks: (List<String>) -> Unit,
    onRemoveBook: (String) -> Unit,
    onDeleteOrder: () -> Unit,
) {
    var reorderMode by rememberSaveable { mutableStateOf(false) }
    var showAddBooksSheet by rememberSaveable { mutableStateOf(false) }
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }

    val wide =
        currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(
            WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND,
        )
    if (wide) {
        WideReadingOrderDetailContent(
            state = current,
            reorderMode = reorderMode,
            onBack = onBack,
            onSetActive = onSetActive,
            onClearActive = onClearActive,
            onReorder = onReorder,
            onRemoveBook = onRemoveBook,
            onToggleReorderMode = { reorderMode = !reorderMode },
            onAddBooksClick = { showAddBooksSheet = true },
            onDeleteClick = { showDeleteDialog = true },
        )
    } else {
        NarrowReadingOrderDetailContent(
            state = current,
            reorderMode = reorderMode,
            onBack = onBack,
            onSetActive = onSetActive,
            onClearActive = onClearActive,
            onReorder = onReorder,
            onRemoveBook = onRemoveBook,
            onToggleReorderMode = { reorderMode = !reorderMode },
            onAddBooksClick = { showAddBooksSheet = true },
            onDeleteClick = { showDeleteDialog = true },
        )
    }

    if (showAddBooksSheet) {
        AddBooksSheet(
            books = current.addableBooks,
            onConfirm = { ids ->
                onAddBooks(ids)
                showAddBooksSheet = false
            },
            onDismiss = { showAddBooksSheet = false },
        )
    }
    if (showDeleteDialog) {
        DeleteOrderDialog(
            orderName = current.order.name,
            onDismissRequest = { showDeleteDialog = false },
            onConfirm = {
                showDeleteDialog = false
                onDeleteOrder()
            },
        )
    }
}

// region not found

@Composable
private fun OrderNotFoundContent(onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(8.dp)) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(Res.string.common_back),
                )
            }
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(Res.string.common_not_found, stringResource(Res.string.reading_orders_title)),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(32.dp),
            )
        }
    }
}

// endregion

// region narrow layout

@Composable
private fun NarrowReadingOrderDetailContent(
    state: ReadingOrderDetailUiState.Ready,
    reorderMode: Boolean,
    onBack: () -> Unit,
    onSetActive: () -> Unit,
    onClearActive: () -> Unit,
    onReorder: (List<String>) -> Unit,
    onRemoveBook: (String) -> Unit,
    onToggleReorderMode: () -> Unit,
    onAddBooksClick: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = RoundedCornerShape(bottomStart = 40.dp, bottomEnd = 40.dp),
        ) {
            Column {
                OrderHeroNavRow(
                    isOwner = state.isOwner,
                    onBack = onBack,
                    onDeleteClick = onDeleteClick,
                    applyStatusBarInset = true,
                )
                OrderHeroCenteredInfo(order = state.order)
            }
        }
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            ActiveRowOrButton(isActive = state.isActive, onSetActive = onSetActive, onClearActive = onClearActive)
            BooksInOrderSection(
                state = state,
                reorderMode = reorderMode,
                onToggleReorderMode = onToggleReorderMode,
                onReorder = onReorder,
                onRemoveBook = onRemoveBook,
                onAddBooksClick = onAddBooksClick,
            )
        }
    }
}

// endregion

// region wide layout

@Composable
private fun WideReadingOrderDetailContent(
    state: ReadingOrderDetailUiState.Ready,
    reorderMode: Boolean,
    onBack: () -> Unit,
    onSetActive: () -> Unit,
    onClearActive: () -> Unit,
    onReorder: (List<String>) -> Unit,
    onRemoveBook: (String) -> Unit,
    onToggleReorderMode: () -> Unit,
    onAddBooksClick: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(20.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Surface(
            modifier = Modifier.width(WIDE_HERO_WIDTH).fillMaxHeight(),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = RoundedCornerShape(32.dp),
        ) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OrderHeroNavRow(
                    isOwner = state.isOwner,
                    onBack = onBack,
                    onDeleteClick = onDeleteClick,
                    applyStatusBarInset = false,
                )
                OrderHeroCenteredInfo(order = state.order)
                Box(modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp)) {
                    ActiveRowOrButton(
                        isActive = state.isActive,
                        onSetActive = onSetActive,
                        onClearActive = onClearActive,
                    )
                }
            }
        }
        Surface(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(32.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                BooksInOrderSection(
                    state = state,
                    reorderMode = reorderMode,
                    onToggleReorderMode = onToggleReorderMode,
                    onReorder = onReorder,
                    onRemoveBook = onRemoveBook,
                    onAddBooksClick = onAddBooksClick,
                )
            }
        }
    }
}

// endregion

// region hero

@Composable
private fun OrderHeroNavRow(
    isOwner: Boolean,
    onBack: () -> Unit,
    onDeleteClick: () -> Unit,
    applyStatusBarInset: Boolean,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .let { if (applyStatusBarInset) it.windowInsetsPadding(WindowInsets.statusBars) else it }
                .padding(start = 8.dp, end = 8.dp, top = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(Res.string.common_back),
            )
        }
        if (isOwner) {
            OrderHeroOverflowMenu(onDeleteClick = onDeleteClick)
        }
    }
}

@Composable
private fun OrderHeroOverflowMenu(onDeleteClick: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(imageVector = Icons.Default.MoreVert, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = {
                    Text(
                        text = stringResource(Res.string.reading_orders_delete_order),
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
                onClick = {
                    expanded = false
                    onDeleteClick()
                },
            )
        }
    }
}

@Composable
private fun OrderHeroCenteredInfo(order: ReadingOrder) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        OrderSpines(seed = order.idString, count = order.bookCount, height = HERO_SPINE_HEIGHT)
        Spacer(Modifier.height(16.dp))
        Text(
            text = order.name,
            fontSize = 28.sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        if (order.attribution.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = order.attribution,
                fontSize = 14.5.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f),
                modifier = Modifier.widthIn(max = ATTRIBUTION_MAX_WIDTH),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val metaTint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
            Text(
                text = booksCountText(order.bookCount),
                style = MaterialTheme.typography.labelLarge,
                color = metaTint,
            )
            Text(text = "·", color = metaTint)
            PrivacyLabel(isPrivate = order.isPrivate, tint = metaTint)
        }
    }
}

// endregion

// region active state

@Composable
private fun ActiveRowOrButton(
    isActive: Boolean,
    onSetActive: () -> Unit,
    onClearActive: () -> Unit,
) {
    if (isActive) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .clickable(onClick = onClearActive)
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Bolt, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(Res.string.reading_orders_active_row),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    } else {
        Button(onClick = onSetActive, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Bolt, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(Res.string.reading_orders_set_active))
        }
    }
}

// endregion

// region books in order

@Composable
private fun BooksInOrderSection(
    state: ReadingOrderDetailUiState.Ready,
    reorderMode: Boolean,
    onToggleReorderMode: () -> Unit,
    onReorder: (List<String>) -> Unit,
    onRemoveBook: (String) -> Unit,
    onAddBooksClick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(Res.string.reading_orders_books_in_order),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
            )
            if (state.isOwner) {
                FilterChip(
                    selected = reorderMode,
                    onClick = onToggleReorderMode,
                    label = { Text(stringResource(Res.string.reading_orders_reorder)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.SwapVert,
                            contentDescription = null,
                            modifier = Modifier.size(FilterChipDefaults.IconSize),
                        )
                    },
                )
            }
        }
        if (reorderMode && state.isOwner) {
            Text(
                text = stringResource(Res.string.reading_orders_reorder_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (reorderMode && state.isOwner) {
            ReorderableBooksList(books = state.books, onReorder = onReorder)
        } else {
            Column {
                state.books.forEachIndexed { index, book ->
                    OrderBookRow(
                        index = index + 1,
                        book = book,
                        reorderMode = false,
                        showOverflow = state.isOwner,
                        onRemove = { onRemoveBook(book.bookId) },
                    )
                }
            }
        }

        if (state.isOwner && state.addableBooks.isNotEmpty()) {
            FilledTonalButton(onClick = onAddBooksClick, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.reading_orders_add_books))
            }
        }
    }
}

/**
 * Drag-to-reorder adapter over [ReorderableList] for the flat (no nesting) reading-order book
 * list: every [ReorderNode] is root-level and non-parenting, so a completed [ReorderMove] always
 * degrades to a plain reorder — this computes the resulting full ordered id list and hands it to
 * [onReorder].
 */
@Composable
private fun ReorderableBooksList(
    books: List<OrderBookRowUi>,
    onReorder: (List<String>) -> Unit,
) {
    val nodes = remember(books) { books.map { ReorderNode(id = it.bookId, parentId = null, canHaveChildren = false) } }
    val booksById = remember(books) { books.associateBy { it.bookId } }
    val indexById = remember(books) { books.mapIndexed { index, book -> book.bookId to index }.toMap() }

    ReorderableList(
        nodes = nodes,
        onMove = { move -> onReorder(books.reorderedIds(move)) },
        itemContent = { nodeId ->
            booksById[nodeId]?.let { book ->
                OrderBookRow(
                    index = (indexById[nodeId] ?: 0) + 1,
                    book = book,
                    reorderMode = true,
                    showOverflow = false,
                    onRemove = {},
                )
            }
        },
    )
}

private fun List<OrderBookRowUi>.reorderedIds(move: ReorderMove): List<String> {
    val ids = map { it.bookId }.toMutableList()
    ids.remove(move.movedId)
    ids.add(move.newIndex.coerceIn(0, ids.size), move.movedId)
    return ids
}

@Composable
private fun OrderBookRow(
    index: Int,
    book: OrderBookRowUi,
    reorderMode: Boolean,
    showOverflow: Boolean,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = index.toString(),
            fontSize = 16.sp,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(22.dp),
        )
        BookCoverImage(
            bookId = book.bookId,
            coverPath = null,
            contentDescription = book.title,
            title = book.title,
            author = book.authorLine,
            modifier = Modifier.size(46.dp).clip(RoundedCornerShape(9.dp)),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = book.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = DurationFormatter.hoursMinutes(book.durationMs.milliseconds),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            reorderMode -> {
                Icon(
                    Icons.Filled.DragHandle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            showOverflow -> {
                OrderBookRowOverflow(onRemove = onRemove)
            }
        }
    }
}

@Composable
private fun OrderBookRowOverflow(onRemove: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.reading_orders_remove_book)) },
                onClick = {
                    expanded = false
                    onRemove()
                },
            )
        }
    }
}

// endregion

// region delete dialog

@Composable
private fun DeleteOrderDialog(
    orderName: String,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(Res.string.reading_orders_delete_order_title)) },
        text = { Text(stringResource(Res.string.reading_orders_delete_order_body, orderName)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(Res.string.common_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(Res.string.common_cancel)) }
        },
    )
}

// endregion
