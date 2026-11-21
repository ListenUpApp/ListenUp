package com.calypsan.listenup.client.features.bookdetail.components

import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.domain.model.DownloadFailureReason
import com.calypsan.listenup.client.domain.model.DownloadPhase
import com.calypsan.listenup.client.domain.model.downloadPhaseOf
import com.calypsan.listenup.client.presentation.bookdetail.byteProgressLabel
import com.calypsan.listenup.client.presentation.bookdetail.byteSizeLabel

/** What the Download control shows, independent of how it is drawn (icon-only on phones, labelled on tablets). */
internal sealed interface DownloadActionKind {
    /** Nothing is on the device yet. */
    data object Download : DownloadActionKind

    /** Queued behind other downloads, nothing moving yet. */
    data object Queued : DownloadActionKind

    /** Queued, held back until the device is on Wi-Fi. */
    data object WaitingForWifi : DownloadActionKind

    /** The system stopped it; it resumes by itself. */
    data object WaitingToResume : DownloadActionKind

    /** Files are moving; [percent] of the bytes are saved. */
    data class Downloading(
        val percent: Int,
    ) : DownloadActionKind

    /** The person paused it with [percent] of the bytes saved. */
    data class PausedByYou(
        val percent: Int,
    ) : DownloadActionKind

    /** Every file is on the device. */
    data object Downloaded : DownloadActionKind

    /** The last attempt failed. */
    data object Retry : DownloadActionKind
}

/** What a tap on the Download control (or the status line's Cancel) does. */
internal enum class DownloadTap { Start, Pause, Cancel, Remove }

/**
 * The line under the action row (canvas decision 1): where a download stands while it is active, held or failed.
 * Resting states (not downloaded, downloaded) have none. [offersCancel] when the button does something else, so Cancel
 * is always one tap away.
 */
internal sealed interface DownloadStatusLine {
    /** Whether the line carries a Cancel. */
    val offersCancel: Boolean

    /** "Downloading · 212 of 640 MB". */
    data class Downloading(
        val progress: String,
    ) : DownloadStatusLine {
        override val offersCancel = true
    }

    /** "Queued · starts after the current download". */
    data object Queued : DownloadStatusLine {
        override val offersCancel = false
    }

    /** "Waiting for Wi-Fi · downloads use Wi-Fi only". */
    data object WaitingForWifi : DownloadStatusLine {
        override val offersCancel = false
    }

    /** "Waiting · resumes automatically". */
    data object Waiting : DownloadStatusLine {
        override val offersCancel = false
    }

    /** "Paused · 212 of 640 MB saved". */
    data class Paused(
        val progress: String,
    ) : DownloadStatusLine {
        override val offersCancel = true
    }

    /** "Couldn’t download · not enough space on this device". */
    data class Failed(
        val reason: DownloadFailureReason,
    ) : DownloadStatusLine {
        override val offersCancel = true
    }
}

/**
 * The Download control's state, its file-size label (spec N2: the size, never the length), its tap, and the status
 * line under the row.
 */
internal data class DownloadAction(
    val kind: DownloadActionKind,
    val sizeLabel: String,
    val tap: DownloadTap,
    val statusLine: DownloadStatusLine? = null,
)

/**
 * Pure: maps the download state onto the control, through the one shared rule ([downloadPhaseOf]). The button does the
 * state's main thing: Download starts, a moving download pauses, a paused one resumes, a failed one retries, and a
 * downloaded one removes (the screen confirms). A queued or waiting download cancels from the button, as before.
 */
internal fun downloadActionFor(
    status: BookDownloadStatus,
    isWaitingForWifi: Boolean,
    sizeBytes: Long,
): DownloadAction {
    val size = byteSizeLabel(sizeBytes)
    return when (val phase = downloadPhaseOf(status, wifiBlocked = isWaitingForWifi)) {
        DownloadPhase.NotDownloaded -> {
            DownloadAction(DownloadActionKind.Download, size, DownloadTap.Start)
        }

        is DownloadPhase.Downloading -> {
            DownloadAction(
                kind = DownloadActionKind.Downloading(phase.percent),
                sizeLabel = size,
                tap = DownloadTap.Pause,
                statusLine = DownloadStatusLine.Downloading(byteProgressLabel(phase.downloadedBytes, phase.totalBytes)),
            )
        }

        DownloadPhase.Queued -> {
            DownloadAction(
                kind = DownloadActionKind.Queued,
                sizeLabel = size,
                tap = DownloadTap.Cancel,
                statusLine = DownloadStatusLine.Queued,
            )
        }

        DownloadPhase.WaitingForWifi -> {
            DownloadAction(
                kind = DownloadActionKind.WaitingForWifi,
                sizeLabel = size,
                tap = DownloadTap.Cancel,
                statusLine = DownloadStatusLine.WaitingForWifi,
            )
        }

        DownloadPhase.WaitingToResume -> {
            DownloadAction(
                kind = DownloadActionKind.WaitingToResume,
                sizeLabel = size,
                tap = DownloadTap.Cancel,
                statusLine = DownloadStatusLine.Waiting,
            )
        }

        is DownloadPhase.PausedByYou -> {
            DownloadAction(
                kind = DownloadActionKind.PausedByYou(phase.percent),
                sizeLabel = size,
                tap = DownloadTap.Start,
                statusLine = DownloadStatusLine.Paused(byteProgressLabel(phase.downloadedBytes, phase.totalBytes)),
            )
        }

        is DownloadPhase.Failed -> {
            DownloadAction(
                kind = DownloadActionKind.Retry,
                sizeLabel = size,
                tap = DownloadTap.Start,
                statusLine = DownloadStatusLine.Failed(phase.reason),
            )
        }

        DownloadPhase.Downloaded -> {
            DownloadAction(DownloadActionKind.Downloaded, size, DownloadTap.Remove)
        }
    }
}
