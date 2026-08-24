package com.calypsan.listenup.client.features.bookdetail

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.design.components.ListenUpDestructiveDialog
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_delete
import listenup.composeapp.generated.resources.book_delete_download
import listenup.composeapp.generated.resources.book_detail_remove_the_downloaded_files_for
import listenup.composeapp.generated.resources.book_detail_you_can_redownload_anytime_by

/**
 * Confirmation dialog for deleting downloaded audiobook files.
 */
@Composable
fun DeleteDownloadDialog(
    bookTitle: String,
    downloadSize: Long,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ListenUpDestructiveDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.book_delete_download),
        text =
            stringResource(Res.string.book_detail_remove_the_downloaded_files_for, bookTitle) +
                "This will free up ${formatFileSize(downloadSize)}. " +
                stringResource(Res.string.book_detail_you_can_redownload_anytime_by),
        confirmText = stringResource(Res.string.common_delete),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        icon = Icons.Default.Delete,
    )
}

/**
 * Format bytes to human-readable size.
 */
@Suppress("MagicNumber")
fun formatFileSize(bytes: Long): String =
    when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${(bytes / (1024.0 * 1024.0) * 10).toInt() / 10.0} MB"
        else -> "${(bytes / (1024.0 * 1024.0 * 1024.0) * 100).toInt() / 100.0} GB"
    }
