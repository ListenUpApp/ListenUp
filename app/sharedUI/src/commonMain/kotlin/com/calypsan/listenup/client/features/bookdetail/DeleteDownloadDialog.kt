package com.calypsan.listenup.client.features.bookdetail

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.design.components.ListenUpAlertDialog
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_remove_download_body
import listenup.composeapp.generated.resources.book_detail_remove_download_confirm
import listenup.composeapp.generated.resources.book_detail_remove_download_keep
import listenup.composeapp.generated.resources.book_detail_remove_download_title
import org.jetbrains.compose.resources.stringResource

/**
 * Confirmation for removing a book's download (spec §2.8): what it frees, and that the book stays in the library, keeps
 * your place and streams when online. Keep / Remove. Not destructive-red: downloading again undoes it.
 */
@Composable
fun DeleteDownloadDialog(
    downloadSize: Long,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ListenUpAlertDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.book_detail_remove_download_title),
        text = stringResource(Res.string.book_detail_remove_download_body, formatFileSize(downloadSize)),
        confirmText = stringResource(Res.string.book_detail_remove_download_confirm),
        onConfirm = onConfirm,
        dismissText = stringResource(Res.string.book_detail_remove_download_keep),
        onDismiss = onDismiss,
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
