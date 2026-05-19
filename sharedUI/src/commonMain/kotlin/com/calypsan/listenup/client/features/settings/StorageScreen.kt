@file:Suppress("MagicNumber")

package com.calypsan.listenup.client.features.settings

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.ListenUpAsyncImage
import com.calypsan.listenup.client.design.components.ListenUpDestructiveDialog
import com.calypsan.listenup.client.features.bookdetail.formatFileSize
import com.calypsan.listenup.client.domain.model.DownloadedBookSummary
import com.calypsan.listenup.client.presentation.storage.DeleteConfirmation
import com.calypsan.listenup.client.presentation.storage.StorageUiState
import com.calypsan.listenup.client.presentation.storage.StorageViewModel
import org.koin.compose.viewmodel.koinViewModel
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_back
import listenup.composeapp.generated.resources.common_delete
import listenup.composeapp.generated.resources.book_delete_download
import listenup.composeapp.generated.resources.book_detail_you_can_redownload_anytime_by
import listenup.composeapp.generated.resources.settings_clear_all
import listenup.composeapp.generated.resources.settings_clear_all_downloads
import listenup.composeapp.generated.resources.settings_downloaded_books
import listenup.composeapp.generated.resources.settings_downloaded_books_will_appear_here
import listenup.composeapp.generated.resources.settings_no_downloads
import listenup.composeapp.generated.resources.common_storage
import listenup.composeapp.generated.resources.settings_you_can_redownload_books_anytime

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
                    icon = Icons.Default.Delete,
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
                    icon = Icons.Default.DeleteSweep,
                )
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.common_storage)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.common_back),
                        )
                    }
                },
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

@Composable
private fun StorageContent(
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

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
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
                Text(
                    text = stringResource(Res.string.settings_downloaded_books),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
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
private fun StorageSummaryCard(
    totalUsed: Long,
    available: Long,
    bookCount: Int,
) {
    val total = totalUsed + available
    val usagePercent = if (total > 0) totalUsed.toFloat() / total else 0f

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
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
                        text = if (bookCount == 1) "1 book downloaded" else "$bookCount books downloaded",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "${formatFileSize(available)} available",
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
                blurHash = book.coverBlurHash,
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
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${formatFileSize(book.sizeBytes)} · ${book.fileCount} files",
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
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(Res.string.book_delete_download),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
