package com.calypsan.listenup.client.features.admin.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.LibraryAddCheck
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.calypsan.listenup.client.design.components.ListenUpScaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.design.TwoPaneMinWidth
import com.calypsan.listenup.client.design.components.BookCoverImage
import com.calypsan.listenup.client.design.components.ExpressiveCheckbox
import com.calypsan.listenup.client.design.components.FullScreenLoadingIndicator
import com.calypsan.listenup.client.design.components.HeroNavRow
import com.calypsan.listenup.client.design.components.ListenUpDestructiveDialog
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicatorSmall
import com.calypsan.listenup.client.design.components.ScallopBadge
import com.calypsan.listenup.client.core.DurationFormatter
import kotlin.time.Duration.Companion.milliseconds
import com.calypsan.listenup.client.domain.model.InboxBookItem
import com.calypsan.listenup.client.presentation.admin.AdminInboxUiState
import com.calypsan.listenup.client.presentation.admin.AdminInboxViewModel
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.admin_books_awaiting_review_count
import listenup.composeapp.generated.resources.admin_books_awaiting_review_s_count
import listenup.composeapp.generated.resources.admin_inbox_deselect_all
import listenup.composeapp.generated.resources.admin_inbox_empty
import listenup.composeapp.generated.resources.admin_inbox_release_count
import listenup.composeapp.generated.resources.admin_inbox_released_count
import listenup.composeapp.generated.resources.admin_inbox_released_count_plural
import listenup.composeapp.generated.resources.admin_inbox_review_edit
import listenup.composeapp.generated.resources.metadata_match_on_audible
import listenup.composeapp.generated.resources.admin_inbox_select_all
import listenup.composeapp.generated.resources.admin_newly_scanned_books_will_appear
import listenup.composeapp.generated.resources.admin_release_anyway
import listenup.composeapp.generated.resources.admin_release_without_collections
import listenup.composeapp.generated.resources.admin_selected_count
import listenup.composeapp.generated.resources.admin_these_books_will_become_visible
import listenup.composeapp.generated.resources.common_administration
import listenup.composeapp.generated.resources.common_back
import listenup.composeapp.generated.resources.common_inbox

/**
 * Admin review-and-release queue for the inbox, rebuilt to the M3 Expressive mockup.
 *
 * Lists freshly-scanned books awaiting triage as expressive selectable rows — an [ExpressiveCheckbox],
 * a cover thumbnail, title / author / duration, and a review-and-edit button. A `primaryContainer`
 * color-block hero carries the title, the awaiting-review count (or a live selection count), and a
 * select-all toggle; the bottom action bar releases the selected books once any are chosen. Tapping a
 * row opens book-edit (where tags / collections are fixed); collection assignment is intentionally not
 * done here. The empty state centres a scalloped inbox glyph at every width.
 *
 * Responsive: below [TwoPaneMinWidth] (960.dp) the screen is a single comfortable column with a
 * rounded-bottom hero; at or above it the hero becomes a horizontal card and the queue flows into a
 * width-driven [GridCells.Adaptive] grid so tablet / desktop widths fill with multiple columns rather
 * than a stretched phone column.
 *
 * The [AdminInboxViewModel] owns all state and actions — this screen is a pure render of
 * [AdminInboxUiState] plus the release-confirmation dialog and the two transient snackbars.
 *
 * @param viewModel The inbox ViewModel (state + selection + release actions, live sync updates).
 * @param onBackClick Navigate back to Admin.
 * @param onBookClick Open book-edit for the tapped book id (fix tags / collections before release).
 */
@Composable
fun AdminInboxScreen(
    viewModel: AdminInboxViewModel,
    onBackClick: () -> Unit,
    onBookClick: (String) -> Unit,
    onMatchClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showReleaseConfirmation by remember { mutableStateOf(false) }

    // Transient mutation-failure error in snackbar (only meaningful in Ready).
    val readyError = (state as? AdminInboxUiState.Ready)?.error
    LaunchedEffect(readyError) {
        readyError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // Release success confirmation (only meaningful in Ready).
    val readyReleasedCount = (state as? AdminInboxUiState.Ready)?.lastReleasedCount
    val releasedSingular = stringResource(Res.string.admin_inbox_released_count, readyReleasedCount ?: 0)
    val releasedPlural = stringResource(Res.string.admin_inbox_released_count_plural, readyReleasedCount ?: 0)
    LaunchedEffect(readyReleasedCount) {
        readyReleasedCount?.let { count ->
            snackbarHostState.showSnackbar(if (count == 1) releasedSingular else releasedPlural)
            viewModel.clearReleaseResult()
        }
    }

    val isWide = currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(TwoPaneMinWidth.value.toInt())

    ListenUpScaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        AdminInboxBody(
            state = state,
            isWide = isWide,
            innerPadding = innerPadding,
            onBackClick = onBackClick,
            onBookClick = onBookClick,
            onMatchClick = onMatchClick,
            onBookSelectionToggle = viewModel::toggleBookSelection,
            onSelectAll = viewModel::selectAll,
            onClearSelection = viewModel::clearSelection,
            onRelease = { showReleaseConfirmation = true },
        )
    }

    // Releasing makes the selected books publicly visible — confirm before committing.
    val ready = state as? AdminInboxUiState.Ready
    if (showReleaseConfirmation && ready != null) {
        val count = ready.selectedCount
        ListenUpDestructiveDialog(
            onDismissRequest = { showReleaseConfirmation = false },
            title = stringResource(Res.string.admin_release_without_collections),
            text =
                "$count book${if (count != 1) "s" else ""} " +
                    stringResource(Res.string.admin_these_books_will_become_visible),
            confirmText = stringResource(Res.string.admin_release_anyway),
            onConfirm = {
                showReleaseConfirmation = false
                viewModel.releaseSelected()
            },
            onDismiss = { showReleaseConfirmation = false },
        )
    }
}

@Composable
private fun AdminInboxBody(
    state: AdminInboxUiState,
    isWide: Boolean,
    innerPadding: PaddingValues,
    onBackClick: () -> Unit,
    onBookClick: (String) -> Unit,
    onMatchClick: (String) -> Unit,
    onBookSelectionToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onRelease: () -> Unit,
) {
    when (state) {
        is AdminInboxUiState.Loading -> {
            FullScreenLoadingIndicator(modifier = Modifier.padding(innerPadding))
        }

        is AdminInboxUiState.Error -> {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
        }

        is AdminInboxUiState.Ready -> {
            if (isWide) {
                InboxWideLayout(
                    state = state,
                    onBackClick = onBackClick,
                    onBookClick = onBookClick,
                    onMatchClick = onMatchClick,
                    onBookSelectionToggle = onBookSelectionToggle,
                    onSelectAll = onSelectAll,
                    onClearSelection = onClearSelection,
                    onRelease = onRelease,
                    modifier = Modifier.padding(innerPadding),
                )
            } else {
                InboxPhoneLayout(
                    state = state,
                    onBackClick = onBackClick,
                    onBookClick = onBookClick,
                    onMatchClick = onMatchClick,
                    onBookSelectionToggle = onBookSelectionToggle,
                    onSelectAll = onSelectAll,
                    onClearSelection = onClearSelection,
                    onRelease = onRelease,
                    modifier = Modifier.padding(innerPadding),
                )
            }
        }
    }
}

// ─────────────────────────── Phone layout ────────────────────────────

@Composable
private fun InboxPhoneLayout(
    state: AdminInboxUiState.Ready,
    onBackClick: () -> Unit,
    onBookClick: (String) -> Unit,
    onMatchClick: (String) -> Unit,
    onBookSelectionToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(1),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = if (state.hasSelection) 110.dp else 24.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                InboxHero(
                    state = state,
                    isWide = false,
                    onBackClick = onBackClick,
                    onSelectAll = onSelectAll,
                    onClearSelection = onClearSelection,
                )
            }

            if (state.hasBooks) {
                inboxRowItems(
                    state = state,
                    big = false,
                    onBookClick = onBookClick,
                    onMatchClick = onMatchClick,
                    onBookSelectionToggle = onBookSelectionToggle,
                )
            } else {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyInbox(big = false, modifier = Modifier.padding(top = 40.dp))
                }
            }
        }

        if (state.hasSelection) {
            InboxBottomBar(
                state = state,
                onRelease = onRelease,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

// ─────────────────────────── Wide layout ─────────────────────────────

private val InboxGridMinColumn = 360.dp

@Composable
private fun InboxWideLayout(
    state: AdminInboxUiState.Ready,
    onBackClick: () -> Unit,
    onBookClick: (String) -> Unit,
    onMatchClick: (String) -> Unit,
    onBookSelectionToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = InboxGridMinColumn),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            InboxHero(
                state = state,
                isWide = true,
                onBackClick = onBackClick,
                onSelectAll = onSelectAll,
                onClearSelection = onClearSelection,
                onRelease = onRelease,
            )
        }

        if (state.hasBooks) {
            inboxRowItems(
                state = state,
                big = true,
                onBookClick = onBookClick,
                onMatchClick = onMatchClick,
                onBookSelectionToggle = onBookSelectionToggle,
            )
        } else {
            item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyInbox(big = true, modifier = Modifier.padding(top = 60.dp))
            }
        }
    }
}

// ─────────────────────────── Row items ───────────────────────────────

private fun LazyGridScope.inboxRowItems(
    state: AdminInboxUiState.Ready,
    big: Boolean,
    onBookClick: (String) -> Unit,
    onMatchClick: (String) -> Unit,
    onBookSelectionToggle: (String) -> Unit,
) {
    items(items = state.books, key = { it.id }) { book ->
        InboxRow(
            book = book,
            isSelected = book.id in state.selectedBookIds,
            isReleasing = state.isReleasing && book.id in state.selectedBookIds,
            big = big,
            onClick = { onBookClick(book.id) },
            onMatch = { onMatchClick(book.id) },
            onSelectionToggle = { onBookSelectionToggle(book.id) },
        )
    }
}

// ─────────────────────────── Hero ────────────────────────────────────

@Composable
private fun InboxHero(
    state: AdminInboxUiState.Ready,
    isWide: Boolean,
    onBackClick: () -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onRelease: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (isWide) {
        InboxWideHero(
            state = state,
            onBackClick = onBackClick,
            onSelectAll = onSelectAll,
            onClearSelection = onClearSelection,
            onRelease = onRelease,
            modifier = modifier,
        )
    } else {
        InboxPhoneHero(
            state = state,
            onBackClick = onBackClick,
            onSelectAll = onSelectAll,
            onClearSelection = onClearSelection,
            modifier = modifier,
        )
    }
}

@Composable
private fun InboxPhoneHero(
    state: AdminInboxUiState.Ready,
    onBackClick: () -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(bottomStart = 40.dp, bottomEnd = 40.dp),
    ) {
        Column(modifier = Modifier.padding(bottom = 22.dp)) {
            HeroNavRow(
                onBack = onBackClick,
                buttonBackground = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.08f),
                actions = {
                    if (state.hasBooks) {
                        SelectAllAction(
                            allSelected = state.allSelected,
                            onSelectAll = onSelectAll,
                            onClearSelection = onClearSelection,
                        )
                    }
                },
            )

            Text(
                text = stringResource(Res.string.common_inbox),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(start = 20.dp, top = 6.dp),
                letterSpacing = (-1.4).sp,
            )
            if (state.hasBooks) {
                Text(
                    text = inboxSubtitle(state),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                    modifier = Modifier.padding(start = 20.dp, top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun InboxWideHero(
    state: AdminInboxUiState.Ready,
    onBackClick: () -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(top = 20.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 30.dp, vertical = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            IconButton(
                onClick = onBackClick,
                modifier =
                    Modifier
                        .size(52.dp)
                        .background(
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.09f),
                            shape = MaterialTheme.shapes.medium,
                        ),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(Res.string.common_back),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }

            Column {
                Text(
                    text = stringResource(Res.string.common_administration).uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    letterSpacing = 1.sp,
                )
                Text(
                    text = stringResource(Res.string.common_inbox),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    letterSpacing = (-1.4).sp,
                )
                if (state.hasBooks) {
                    Text(
                        text = inboxSubtitle(state),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            if (state.hasBooks) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SelectAllPillButton(
                        allSelected = state.allSelected,
                        onSelectAll = onSelectAll,
                        onClearSelection = onClearSelection,
                    )
                    if (state.hasSelection) {
                        ReleaseButton(
                            count = state.selectedCount,
                            isReleasing = state.isReleasing,
                            onRelease = onRelease,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun inboxSubtitle(state: AdminInboxUiState.Ready): String =
    when {
        state.hasSelection -> {
            stringResource(Res.string.admin_selected_count, state.selectedCount)
        }

        state.bookIds.size == 1 -> {
            stringResource(Res.string.admin_books_awaiting_review_count, state.bookIds.size)
        }

        else -> {
            stringResource(Res.string.admin_books_awaiting_review_s_count, state.bookIds.size)
        }
    }

// ─────────────────────────── Hero actions ────────────────────────────

@Composable
private fun SelectAllAction(
    allSelected: Boolean,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
) {
    val label =
        if (allSelected) {
            stringResource(Res.string.admin_inbox_deselect_all)
        } else {
            stringResource(Res.string.admin_inbox_select_all)
        }
    Surface(
        onClick = { if (allSelected) onClearSelection() else onSelectAll() },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.08f),
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.size(48.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Outlined.SelectAll,
                contentDescription = label,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun SelectAllPillButton(
    allSelected: Boolean,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
) {
    val label =
        if (allSelected) {
            stringResource(Res.string.admin_inbox_deselect_all)
        } else {
            stringResource(Res.string.admin_inbox_select_all)
        }
    Surface(
        onClick = { if (allSelected) onClearSelection() else onSelectAll() },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.10f),
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.SelectAll,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun ReleaseButton(
    count: Int,
    isReleasing: Boolean,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onRelease,
        enabled = !isReleasing,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 14.dp),
    ) {
        if (isReleasing) {
            ListenUpLoadingIndicatorSmall()
        } else {
            Icon(
                imageVector = Icons.Outlined.LibraryAddCheck,
                contentDescription = null,
                modifier = Modifier.size(21.dp),
            )
            Spacer(modifier = Modifier.width(9.dp))
            Text(
                text = stringResource(Res.string.admin_inbox_release_count, count),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

// ─────────────────────────── Bottom action bar (phone) ───────────────

@Composable
private fun InboxBottomBar(
    state: AdminInboxUiState.Ready,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Box(
            modifier =
                Modifier.padding(
                    PaddingValues(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 28.dp),
                ),
        ) {
            ReleaseButton(
                count = state.selectedCount,
                isReleasing = state.isReleasing,
                onRelease = onRelease,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            )
        }
    }
}

// ─────────────────────────── Inbox row ───────────────────────────────

@Composable
private fun InboxRow(
    book: InboxBookItem,
    isSelected: Boolean,
    isReleasing: Boolean,
    big: Boolean,
    onClick: () -> Unit,
    onMatch: () -> Unit,
    onSelectionToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val coverSize = if (big) 64.dp else 56.dp
    val editTileSize = if (big) 48.dp else 44.dp
    val rowColor =
        if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        }
    val titleColor =
        if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    val subColor =
        if (isSelected) {
            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.extraLarge)
                .background(rowColor)
                .clickable(enabled = !isReleasing, onClick = onClick)
                .padding(
                    horizontal = if (big) 18.dp else 14.dp,
                    vertical = if (big) 14.dp else 12.dp,
                ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(15.dp),
    ) {
        ExpressiveCheckbox(
            checked = isSelected,
            onCheckedChange = { if (!isReleasing) onSelectionToggle() },
        )

        BookCoverImage(
            bookId = book.id,
            coverPath = book.coverPath,
            coverHash = book.coverHash,
            title = book.title,
            author = book.author,
            contentDescription = book.title,
            modifier = Modifier.size(coverSize).clip(MaterialTheme.shapes.medium),
        )

        InboxRowText(
            book = book,
            isSelected = isSelected,
            big = big,
            titleColor = titleColor,
            subColor = subColor,
            modifier = Modifier.weight(1f),
        )

        if (isReleasing) {
            ListenUpLoadingIndicatorSmall()
        } else {
            MatchOnAudibleButton(
                isSelected = isSelected,
                size = editTileSize,
                onClick = onMatch,
            )
            ReviewEditButton(
                isSelected = isSelected,
                size = editTileSize,
                onClick = onClick,
            )
        }
    }
}

@Composable
private fun InboxRowText(
    book: InboxBookItem,
    isSelected: Boolean,
    big: Boolean,
    titleColor: Color,
    subColor: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = book.title,
            style = if (big) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = titleColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        book.author?.let { author ->
            Text(
                text = author,
                style = MaterialTheme.typography.bodySmall,
                color = subColor,
                modifier = Modifier.padding(top = 2.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            modifier = Modifier.padding(top = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Schedule,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = if (isSelected) titleColor else MaterialTheme.colorScheme.tertiary,
            )
            Text(
                text = DurationFormatter.hoursMinutes(book.durationMs.milliseconds),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = subColor,
            )
        }
    }
}

@Composable
private fun MatchOnAudibleButton(
    isSelected: Boolean,
    size: Dp,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color =
            if (isSelected) {
                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        contentColor =
            if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(size),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Outlined.AutoAwesome,
                contentDescription = stringResource(Res.string.metadata_match_on_audible),
                modifier = Modifier.size(if (size > 46.dp) 23.dp else 21.dp),
            )
        }
    }
}

@Composable
private fun ReviewEditButton(
    isSelected: Boolean,
    size: Dp,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color =
            if (isSelected) {
                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        contentColor =
            if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(size),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Outlined.Edit,
                contentDescription = stringResource(Res.string.admin_inbox_review_edit),
                modifier = Modifier.size(if (size > 46.dp) 23.dp else 21.dp),
            )
        }
    }
}

// ─────────────────────────── Empty state ─────────────────────────────

@Composable
private fun EmptyInbox(
    big: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 30.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ScallopBadge(
            size = if (big) 120.dp else 104.dp,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Icon(
                imageVector = Icons.Outlined.Inbox,
                contentDescription = null,
                modifier = Modifier.size(if (big) 56.dp else 48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(22.dp))
        Text(
            text = stringResource(Res.string.admin_inbox_empty),
            style = if (big) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(Res.string.admin_newly_scanned_books_will_appear),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 300.dp),
        )
    }
}
