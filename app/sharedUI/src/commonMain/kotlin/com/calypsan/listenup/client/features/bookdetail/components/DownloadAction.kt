package com.calypsan.listenup.client.features.bookdetail.components

import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.features.bookdetail.formatFileSize

/** What the Download control shows, independent of how it is drawn (icon-only on phones, labelled on tablets). */
internal sealed interface DownloadActionKind {
    /** Nothing is on the device yet. */
    data object Download : DownloadActionKind

    /** Queued behind other downloads, nothing moving yet. */
    data object Queued : DownloadActionKind

    /** Queued, held back until the device is on Wi-Fi. */
    data object WaitingForWifi : DownloadActionKind

    /** Files are moving; [percent] of the bytes are saved. */
    data class Downloading(
        val percent: Int,
    ) : DownloadActionKind

    /** Every file is on the device. */
    data object Downloaded : DownloadActionKind

    /** The last attempt failed or was paused. */
    data object Retry : DownloadActionKind
}

/** What a tap on the Download control does. */
internal enum class DownloadTap { Start, Cancel, Remove }

/** The Download control's state, its file-size label (spec N2: the size, never the length) and its tap. */
internal data class DownloadAction(
    val kind: DownloadActionKind,
    val sizeLabel: String,
    val tap: DownloadTap,
)

/** Pure: maps the download state onto the control. */
internal fun downloadActionFor(
    status: BookDownloadStatus,
    isWaitingForWifi: Boolean,
    sizeBytes: Long,
): DownloadAction {
    val size = formatFileSize(sizeBytes)
    return when (status) {
        is BookDownloadStatus.NotDownloaded -> {
            DownloadAction(DownloadActionKind.Download, size, DownloadTap.Start)
        }

        is BookDownloadStatus.InProgress -> {
            when {
                status.downloadingFiles > 0 -> {
                    DownloadAction(
                        DownloadActionKind.Downloading(percent = (status.progress * PERCENT).toInt().coerceIn(0, PERCENT)),
                        size,
                        DownloadTap.Cancel,
                    )
                }

                isWaitingForWifi -> {
                    DownloadAction(DownloadActionKind.WaitingForWifi, size, DownloadTap.Cancel)
                }

                else -> {
                    DownloadAction(DownloadActionKind.Queued, size, DownloadTap.Cancel)
                }
            }
        }

        is BookDownloadStatus.Completed -> {
            DownloadAction(DownloadActionKind.Downloaded, size, DownloadTap.Remove)
        }

        is BookDownloadStatus.Failed, is BookDownloadStatus.Paused -> {
            DownloadAction(DownloadActionKind.Retry, size, DownloadTap.Start)
        }
    }
}

private const val PERCENT = 100
