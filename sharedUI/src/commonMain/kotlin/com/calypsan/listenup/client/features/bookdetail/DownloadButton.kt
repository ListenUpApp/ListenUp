package com.calypsan.listenup.client.features.bookdetail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.CircularProgressIndicator
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicatorSmall
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_cancel_download
import listenup.composeapp.generated.resources.book_delete_download
import listenup.composeapp.generated.resources.book_detail_download
import listenup.composeapp.generated.resources.book_detail_download_book
import listenup.composeapp.generated.resources.book_detail_downloaded
import listenup.composeapp.generated.resources.book_detail_progresspercent
import listenup.composeapp.generated.resources.book_detail_queued
import listenup.composeapp.generated.resources.common_retry
import listenup.composeapp.generated.resources.book_detail_retry_download
import listenup.composeapp.generated.resources.book_detail_preparing_on_server
import listenup.composeapp.generated.resources.book_detail_waiting_for_wifi

/**
 * Download button with visual state for book detail screen.
 *
 * States:
 * - Not downloaded: Download icon, tap to queue
 * - Queued (normal): Progress spinner, tap to cancel
 * - Queued (waiting for WiFi): WiFi-off icon, tap to cancel
 * - Downloading: Progress circle with %, tap to cancel
 * - Downloaded: Trash icon, tap to delete
 * - Failed/Paused: Retry icon, tap to resume
 *
 * @param isWaitingForWifi True when download is queued but waiting for WiFi connection
 *                         (wifiOnlyDownloads enabled + not on WiFi). Shows WiFi-off icon.
 */
@Composable
fun DownloadButton(
    status: BookDownloadStatus,
    onDownloadClick: () -> Unit,
    onCancelClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier,
    isWaitingForWifi: Boolean = false,
    enabled: Boolean = true,
) {
    val containerColor =
        if (enabled) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
        }
    val contentColor =
        if (enabled) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        }

    Surface(
        modifier = modifier.size(56.dp),
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize(),
        ) {
            when (status) {
                is BookDownloadStatus.NotDownloaded -> {
                    IconButton(onClick = onDownloadClick) {
                        Icon(
                            Icons.Outlined.Download,
                            contentDescription = stringResource(Res.string.book_detail_download_book),
                            tint = contentColor,
                        )
                    }
                }

                is BookDownloadStatus.InProgress -> {
                    IconButton(onClick = onCancelClick) {
                        when {
                            status.downloadingFiles > 0 -> {
                                Box(contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(
                                        progress = { status.progress },
                                        modifier = Modifier.size(32.dp),
                                        strokeWidth = 3.dp,
                                        color = contentColor,
                                        trackColor = contentColor.copy(alpha = 0.3f),
                                    )
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = stringResource(Res.string.book_detail_cancel_download),
                                        modifier = Modifier.size(12.dp),
                                        tint = contentColor.copy(alpha = 0.6f),
                                    )
                                }
                            }

                            // Bug 4 (W8 Phase D): server is transcoding. Distinct visual state
                            // so the user knows the worker isn't stuck — server-side work in flight.
                            status.waitingForServerFiles > 0 -> {
                                ListenUpLoadingIndicatorSmall()
                            }

                            isWaitingForWifi -> {
                                Icon(
                                    Icons.Default.WifiOff,
                                    contentDescription = stringResource(Res.string.book_detail_waiting_for_wifi),
                                    tint = contentColor.copy(alpha = 0.7f),
                                )
                            }

                            else -> {
                                ListenUpLoadingIndicatorSmall()
                            }
                        }
                    }
                }

                is BookDownloadStatus.Completed -> {
                    IconButton(onClick = onDeleteClick) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = stringResource(Res.string.book_delete_download),
                            tint = contentColor,
                        )
                    }
                }

                is BookDownloadStatus.Failed,
                is BookDownloadStatus.Paused,
                -> {
                    IconButton(onClick = onDownloadClick) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(Res.string.book_detail_retry_download),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Expanded download button with text label for wider layouts.
 *
 * @param isWaitingForWifi True when download is queued but waiting for WiFi connection
 *                         (wifiOnlyDownloads enabled + not on WiFi). Shows "Waiting for WiFi".
 */
@Composable
fun DownloadButtonExpanded(
    status: BookDownloadStatus,
    onDownloadClick: () -> Unit,
    onCancelClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier,
    isWaitingForWifi: Boolean = false,
) {
    val onClick =
        when (status) {
            is BookDownloadStatus.NotDownloaded -> onDownloadClick
            is BookDownloadStatus.InProgress -> onCancelClick
            is BookDownloadStatus.Completed -> onDeleteClick
            is BookDownloadStatus.Failed -> onDownloadClick
            is BookDownloadStatus.Paused -> onDownloadClick
        }

    OutlinedButton(
        onClick = onClick,
        modifier =
            modifier
                .fillMaxWidth()
                .height(52.dp),
        shape = RoundedCornerShape(26.dp),
    ) {
        when (status) {
            is BookDownloadStatus.NotDownloaded -> {
                Icon(Icons.Outlined.Download, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(Res.string.book_detail_download))
            }

            is BookDownloadStatus.InProgress -> {
                when {
                    status.downloadingFiles > 0 -> {
                        val progressPercent = (status.progress * 100).toInt()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                progress = { status.progress },
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(Res.string.book_detail_progresspercent, progressPercent))
                        }
                    }

                    // Bug 4 (W8 Phase D): server is transcoding. Distinct text state
                    // so the user knows the worker isn't stuck — server-side work in flight.
                    status.waitingForServerFiles > 0 -> {
                        ListenUpLoadingIndicatorSmall()
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(Res.string.book_detail_preparing_on_server))
                    }

                    isWaitingForWifi -> {
                        Icon(
                            Icons.Default.WifiOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(Res.string.book_detail_waiting_for_wifi))
                    }

                    else -> {
                        ListenUpLoadingIndicatorSmall()
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(Res.string.book_detail_queued))
                    }
                }
            }

            is BookDownloadStatus.Completed -> {
                Icon(Icons.Outlined.Delete, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(Res.string.book_detail_downloaded))
            }

            is BookDownloadStatus.Failed,
            is BookDownloadStatus.Paused,
            -> {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(Res.string.common_retry), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
