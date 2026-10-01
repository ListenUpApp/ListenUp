package com.calypsan.listenup.client.features.settings

import com.calypsan.listenup.client.design.components.ListenUpTopAppBar
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import com.calypsan.listenup.client.design.components.ListenUpAlertDialog
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.window.core.layout.WindowSizeClass
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.components.HeldLabel
import com.calypsan.listenup.client.design.components.ListenUpAsyncImage
import com.calypsan.listenup.client.design.components.ListenUpDestructiveDialog
import com.calypsan.listenup.client.design.components.ListenUpScaffold
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.domain.model.DownloadedBookSummary
import com.calypsan.listenup.client.features.bookdetail.formatFileSize
import com.calypsan.listenup.client.presentation.storage.DeleteConfirmation
import com.calypsan.listenup.client.presentation.storage.StorageUiState
import com.calypsan.listenup.client.presentation.storage.StorageViewModel
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.admin_held_release_to_play
import listenup.composeapp.generated.resources.book_delete_download
import listenup.composeapp.generated.resources.book_detail_you_can_redownload_anytime_by
import listenup.composeapp.generated.resources.common_delete
import listenup.composeapp.generated.resources.common_ok
import listenup.composeapp.generated.resources.common_storage
import listenup.composeapp.generated.resources.settings_cant_delete_playing_message
import listenup.composeapp.generated.resources.settings_cant_delete_playing_title
import listenup.composeapp.generated.resources.settings_book_downloaded_count
import listenup.composeapp.generated.resources.settings_books_downloaded_count
import listenup.composeapp.generated.resources.settings_clear_all
import listenup.composeapp.generated.resources.settings_clear_all_downloads
import listenup.composeapp.generated.resources.settings_downloaded_books
import listenup.composeapp.generated.resources.settings_downloaded_books_will_appear_here
import listenup.composeapp.generated.resources.settings_no_downloads
import listenup.composeapp.generated.resources.settings_storage_available
import listenup.composeapp.generated.resources.settings_storage_size_files
import listenup.composeapp.generated.resources.settings_you_can_redownload_books_anytime
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * Storage management screen showing downloaded books and storage usage.
 *
 * Displays:
 * - Total storage used by downloads
 * - Storage usage bar
 * - List of downloaded books with size
 * - Delete individual downloads
 * - Clear all downloads option
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen(
    onNavigateBack: () -> Unit,
    viewModel: StorageViewModel = koinViewModel(),
) {
    val haptics = LocalHaptics.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Handle delete confirmation dialogs
    state.deleteConfirmation?.let { confirmation ->
        when (confirmation) {
            is DeleteConfirmation.SingleBook -> {
                ListenUpDestructiveDialog(
                    onDismissRequest = viewModel::cancelDelete,
                    title = stringResource(Res.string.book_delete_download),
                    text =
                        "Remove the downloaded files for \"${confirmation.book.title}\"? " +
                            "This will free up ${formatFileSize(confirmation.book.sizeBytes)}. " +
                            stringResource(Res.string.book_detail_you_can_redownload_anytime_by),
                    confirmText = stringResource(Res.string.common_delete),
                    onConfirm = viewModel::executeDelete,
                    onDismiss = viewModel::cancelDelete,
                    icon = Icons.Outlined.Delete,
                )
            }

            is DeleteConfirmation.AllDownloads -> {
                ListenUpDestructiveDialog(
                    onDismissRequest = viewModel::cancelDelete,
                    title = stringResource(Res.string.settings_clear_all_downloads),
                    text =
                        "Remove all ${state.downloadedBooks.size} downloaded books? " +
                            "This will free up ${formatFileSize(state.totalStorageUsed)}. " +
                            stringResource(Res.string.settings_you_can_redownload_books_anytime),
                    confirmText = stringResource(Res.string.settings_clear_all),
                    onConfirm = viewModel::executeDelete,
                    onDismiss = viewModel::cancelDelete,
                    icon = Icons.Outlined.DeleteSweep,
                )
            }
        }
    }

    // Never-stranded notice (B9): a delete was refused because the target book is currently
    // playing. Surfacing the reason — instead of silently no-op'ing the delete — tells the user
    // exactly how to proceed (stop playback, then delete).
    state.blockedDeletionTitle?.let { title ->
        ListenUpAlertDialog(
            onDismissRequest = viewModel::dismissDeleteBlocked,
            title = stringResource(Res.string.settings_cant_delete_playing_title),
            text = stringResource(Res.string.settings_cant_delete_playing_message, title),
            confirmText = stringResource(Res.string.common_ok),
            onConfirm = viewModel::dismissDeleteBlocked,
            dismissText = null,
        )
    }

    ListenUpScaffold(
        topBar = {
            ListenUpTopAppBar(
                title = stringResource(Res.string.common_storage),
                onBack = onNavigateBack,
                actions = {
                    if (state.downloadedBooks.isNotEmpty()) {
                        TextButton(
                            onClick = viewModel::confirmClearAll,
                            enabled = !state.isDeleting,
                        ) {
                            Text(
                                text = stringResource(Res.string.settings_clear_all),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        StorageContent(
            state = state,
            onDeleteBook = viewModel::confirmDeleteBook,
            modifier = Modifier.padding(padding),
        )
    }
}

/**
 * The screen's loaded body, hosted by its scaffold. A phone stacks the usage summary over the list of
 * downloads. From the expanded width up the summary becomes a side panel and the downloads flow into
 * a [GridCells.Adaptive] grid beside it — the tablet sees what is using the space and how much is left
 * at once, without the summary scrolling away. Between the two, a small tablet has no room for the
 * panel, so the summary heads the page and the downloads flow into the same grid under it.
 */
@Composable
internal fun StorageContent(
    state: StorageUiState,
    onDeleteBook: (DownloadedBookSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.isLoading) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        return
    }

    val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass
    when {
        windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND) -> {
            StorageWideLayout(state = state, onDeleteBook = onDeleteBook, modifier = modifier)
        }

        windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND) -> {
            StorageMediumLayout(state = state, onDeleteBook = onDeleteBook, modifier = modifier)
        }

        else -> {
            StoragePhoneLayout(state = state, onDeleteBook = onDeleteBook, modifier = modifier)
        }
    }
}

/** A small tablet: the summary across the top, then the downloads in columns. */
@Composable
private fun StorageMediumLayout(
    state: StorageUiState,
    onDeleteBook: (DownloadedBookSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = DownloadGridMinColumn),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Spacing.screenMargin, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(Spacing.itemGap),
        verticalArrangement = Arrangement.spacedBy(Spacing.itemGap),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            StorageSummaryCard(
                totalUsed = state.totalStorageUsed,
                available = state.availableStorage,
                bookCount = state.downloadedBooks.size,
            )
        }
        if (state.downloadedBooks.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyDownloadsMessage()
            }
        } else {
            item(span = { GridItemSpan(maxLineSpan) }) {
                DownloadedBooksHeading(modifier = Modifier.padding(top = 8.dp))
            }
            items(
                items = state.downloadedBooks,
                key = { it.bookId },
            ) { book ->
                DownloadedBookItem(
                    book = book,
                    onDelete = { onDeleteBook(book) },
                    isDeleting = state.isDeleting,
                )
            }
        }
    }
}

@Composable
private fun StoragePhoneLayout(
    state: StorageUiState,
    onDeleteBook: (DownloadedBookSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Storage summary card
        item {
            StorageSummaryCard(
                totalUsed = state.totalStorageUsed,
                available = state.availableStorage,
                bookCount = state.downloadedBooks.size,
            )
        }

        if (state.downloadedBooks.isEmpty()) {
            item {
                EmptyDownloadsMessage()
            }
        } else {
            item {
                DownloadedBooksHeading(modifier = Modifier.padding(top = 8.dp))
            }

            items(
                items = state.downloadedBooks,
                key = { it.bookId },
            ) { book ->
                DownloadedBookItem(
                    book = book,
                    onDelete = { onDeleteBook(book) },
                    isDeleting = state.isDeleting,
                )
            }
        }
    }
}

/** Width of the wide layout's summary panel — one comfortable card column. */
private val SummaryPanelWidth = 360.dp

@Composable
private fun StorageWideLayout(
    state: StorageUiState,
    onDeleteBook: (DownloadedBookSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxSize().padding(horizontal = Spacing.screenMargin),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sectionGap),
    ) {
        StorageSummaryCard(
            totalUsed = state.totalStorageUsed,
            available = state.availableStorage,
            bookCount = state.downloadedBooks.size,
            modifier = Modifier.width(SummaryPanelWidth).padding(vertical = 16.dp),
        )

        if (state.downloadedBooks.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                EmptyDownloadsMessage()
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = DownloadGridMinColumn),
                modifier = Modifier.weight(1f).fillMaxHeight(),
                contentPadding = PaddingValues(vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(Spacing.itemGap),
                verticalArrangement = Arrangement.spacedBy(Spacing.itemGap),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    DownloadedBooksHeading()
                }
                items(
                    items = state.downloadedBooks,
                    key = { it.bookId },
                ) { book ->
                    DownloadedBookItem(
                        book = book,
                        onDelete = { onDeleteBook(book) },
                        isDeleting = state.isDeleting,
                    )
                }
            }
        }
    }
}

/**
 * The narrowest a downloaded-book card gets before the column count drops — narrow enough that a
 * 600dp small tablet still gets two columns.
 */
private val DownloadGridMinColumn = 264.dp

@Composable
private fun DownloadedBooksHeading(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(Res.string.settings_downloaded_books),
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier,
    )
}

@Composable
private fun StorageSummaryCard(
    totalUsed: Long,
    available: Long,
    bookCount: Int,
    modifier: Modifier = Modifier,
) {
    val total = totalUsed + available
    val usagePercent = if (total > 0) totalUsed.toFloat() / total else 0f

    Card(
        modifier = modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = formatFileSize(totalUsed),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        text =
                            stringResource(
                                if (bookCount ==
                                    1
                                ) {
                                    Res.string.settings_book_downloaded_count
                                } else {
                                    Res.string.settings_books_downloaded_count
                                },
                                bookCount,
                            ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = stringResource(Res.string.settings_storage_available, formatFileSize(available)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            LinearProgressIndicator(
                progress = { usagePercent },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(MaterialTheme.shapes.small),
                trackColor = MaterialTheme.colorScheme.surfaceContainerLow,
            )
        }
    }
}

@Composable
private fun EmptyDownloadsMessage() {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.CloudDownload,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(Res.string.settings_no_downloads),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = stringResource(Res.string.settings_downloaded_books_will_appear_here),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DownloadedBookItem(
    book: DownloadedBookSummary,
    onDelete: () -> Unit,
    isDeleting: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Book cover
            ListenUpAsyncImage(
                path = null,
                contentDescription = book.title,
                modifier = Modifier.size(56.dp),
            )

            // Book info
            Column(
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (book.authorNames.isNotEmpty()) {
                    Text(
                        text = book.authorNames,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // A held book's download stays — bytes on disk are never stranded — but it can't
                // be played until it is released (spec §9). There is no Play on this row to disable.
                if (book.isHeld) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        HeldLabel()
                        Text(
                            text = stringResource(Res.string.admin_held_release_to_play),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text =
                        stringResource(
                            Res.string.settings_storage_size_files,
                            formatFileSize(book.sizeBytes),
                            book.fileCount,
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Delete button
            IconButton(
                onClick = onDelete,
                enabled = !isDeleting,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = stringResource(Res.string.book_delete_download),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
