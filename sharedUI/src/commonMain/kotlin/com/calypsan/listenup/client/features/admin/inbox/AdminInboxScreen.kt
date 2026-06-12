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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Publish
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.design.components.BookCoverImage
import com.calypsan.listenup.client.design.components.FullScreenLoadingIndicator
import com.calypsan.listenup.client.design.components.ListenUpDestructiveDialog
import com.calypsan.listenup.client.design.components.ListenUpExtendedFab
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicatorSmall
import com.calypsan.listenup.client.design.util.formatDuration
import com.calypsan.listenup.client.domain.model.InboxBookItem
import com.calypsan.listenup.client.presentation.admin.AdminInboxUiState
import com.calypsan.listenup.client.presentation.admin.AdminInboxViewModel
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.admin_inbox_empty
import listenup.composeapp.generated.resources.admin_newly_scanned_books_will_appear
import listenup.composeapp.generated.resources.admin_release_anyway
import listenup.composeapp.generated.resources.admin_release_without_collections
import listenup.composeapp.generated.resources.admin_these_books_will_become_visible
import listenup.composeapp.generated.resources.admin_books_awaiting_review_count
import listenup.composeapp.generated.resources.admin_books_awaiting_review_s_count
import listenup.composeapp.generated.resources.admin_selected_count
import listenup.composeapp.generated.resources.common_inbox
import listenup.composeapp.generated.resources.common_release

/**
 * Admin review-and-release queue for the collection inbox (Layout A).
 *
 * Lists newly-scanned books awaiting review as hydrated rows — cover thumbnail, title, author,
 * and duration with a leading selection checkbox. Tapping a row navigates to book-edit (where
 * tags/collections are fixed); the bottom bulk action releases the selected books, making them
 * publicly visible. Collection assignment is intentionally NOT done here — it lives in book-edit.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminInboxScreen(
    viewModel: AdminInboxViewModel,
    onBackClick: () -> Unit,
    onBookClick: (String) -> Unit,
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
    LaunchedEffect(readyReleasedCount) {
        readyReleasedCount?.let { count ->
            snackbarHostState.showSnackbar("Released $count book${if (count != 1) "s" else ""}")
            viewModel.clearReleaseResult()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            AdminInboxTopBar(
                ready = state as? AdminInboxUiState.Ready,
                onBackClick = onBackClick,
                onClearSelection = viewModel::clearSelection,
                onSelectAll = viewModel::selectAll,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            val ready = state as? AdminInboxUiState.Ready
            if (ready != null && ready.hasSelection) {
                ListenUpExtendedFab(
                    onClick = { showReleaseConfirmation = true },
                    icon = Icons.Outlined.Publish,
                    text = "${stringResource(Res.string.common_release)} (${ready.selectedCount})",
                    isLoading = ready.isReleasing,
                )
            }
        },
    ) { innerPadding ->
        AdminInboxBody(
            state = state,
            innerPadding = innerPadding,
            onBookClick = onBookClick,
            onBookSelectionToggle = viewModel::toggleBookSelection,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("CognitiveComplexMethod")
private fun AdminInboxTopBar(
    ready: AdminInboxUiState.Ready?,
    onBackClick: () -> Unit,
    onClearSelection: () -> Unit,
    onSelectAll: () -> Unit,
) {
    TopAppBar(
        title = {
            if (ready != null && ready.hasSelection) {
                Text(stringResource(Res.string.admin_selected_count, ready.selectedCount))
            } else {
                Text(stringResource(Res.string.common_inbox))
            }
        },
        navigationIcon = {
            IconButton(onClick = {
                if (ready != null && ready.hasSelection) {
                    onClearSelection()
                } else {
                    onBackClick()
                }
            }) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
            }
        },
        actions = {
            if (ready != null && ready.hasBooks) {
                IconButton(onClick = {
                    if (ready.allSelected) onClearSelection() else onSelectAll()
                }) {
                    Icon(
                        imageVector =
                            if (ready.allSelected) Icons.Outlined.CheckBox else Icons.Outlined.SelectAll,
                        contentDescription = if (ready.allSelected) "Deselect all" else "Select all",
                    )
                }
            }
        },
    )
}

@Composable
private fun AdminInboxBody(
    state: AdminInboxUiState,
    innerPadding: PaddingValues,
    onBookClick: (String) -> Unit,
    onBookSelectionToggle: (String) -> Unit,
) {
    when (state) {
        is AdminInboxUiState.Loading -> {
            FullScreenLoadingIndicator()
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
                )
            }
        }

        is AdminInboxUiState.Ready -> {
            AdminInboxReadyContent(
                state = state,
                onBookClick = onBookClick,
                onBookSelectionToggle = onBookSelectionToggle,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}

@Composable
private fun AdminInboxReadyContent(
    state: AdminInboxUiState.Ready,
    onBookClick: (String) -> Unit,
    onBookSelectionToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.hasBooks) {
        EmptyInboxMessage(modifier = modifier)
    } else {
        LazyColumn(
            modifier =
                modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
        ) {
            item {
                Text(
                    text =
                        if (state.bookIds.size == 1) {
                            stringResource(Res.string.admin_books_awaiting_review_count, state.bookIds.size)
                        } else {
                            stringResource(Res.string.admin_books_awaiting_review_s_count, state.bookIds.size)
                        },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            items(items = state.books, key = { it.id }) { book ->
                InboxBookRow(
                    book = book,
                    isSelected = book.id in state.selectedBookIds,
                    isReleasing = state.isReleasing && book.id in state.selectedBookIds,
                    onClick = { onBookClick(book.id) },
                    onSelectionToggle = { onBookSelectionToggle(book.id) },
                )
            }

            item {
                Spacer(modifier = Modifier.height(88.dp)) // Space for the release FAB.
            }
        }
    }
}

@Composable
private fun InboxBookRow(
    book: InboxBookItem,
    isSelected: Boolean,
    isReleasing: Boolean,
    onClick: () -> Unit,
    onSelectionToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .background(
                    if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                ).clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Checkbox(
            checked = isSelected,
            onCheckedChange = { onSelectionToggle() },
            enabled = !isReleasing,
        )

        BookCoverImage(
            bookId = book.id,
            coverPath = book.coverPath,
            contentDescription = book.title,
            modifier =
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp)),
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = book.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            book.author?.let { author ->
                Text(
                    text = author,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = formatDuration(book.durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }

        if (isReleasing) {
            ListenUpLoadingIndicatorSmall()
        }
    }
}

@Composable
private fun EmptyInboxMessage(modifier: Modifier = Modifier) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Inbox,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(64.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(Res.string.admin_inbox_empty),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(Res.string.admin_newly_scanned_books_will_appear),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
