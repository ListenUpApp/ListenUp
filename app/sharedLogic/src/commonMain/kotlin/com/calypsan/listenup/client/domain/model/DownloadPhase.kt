package com.calypsan.listenup.client.domain.model

/**
 * Where a book's download stands, as a person reads it. One rule for every surface that shows a download (Book
 * Detail's control and status line, the Library cover badge, Storage › Downloads, the Android notification), so they
 * can never disagree about whether a download is moving, waiting, or needs the person.
 *
 * Derived by [downloadPhaseOf] from the aggregated [BookDownloadStatus] and whether Wi-Fi-only is holding downloads
 * back right now.
 */
sealed interface DownloadPhase {
    /** Nothing of the book is on the device. */
    data object NotDownloaded : DownloadPhase

    /** Bytes are moving: [downloadedBytes] of [totalBytes] are saved. */
    data class Downloading(
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : DownloadPhase {
        /** Saved share, 0–100. */
        val percent: Int = percentOf(downloadedBytes, totalBytes)
    }

    /** Waiting behind the download in front of it; starts by itself. */
    data object Queued : DownloadPhase

    /** Held because Wi-Fi-only is on and the device is not on Wi-Fi; starts by itself on Wi-Fi. */
    data object WaitingForWifi : DownloadPhase

    /** The system stopped it (a lost connection, Doze, a time limit); it resumes by itself. */
    data object WaitingToResume : DownloadPhase

    /** The person paused it with [downloadedBytes] of [totalBytes] saved; only they resume it. */
    data class PausedByYou(
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : DownloadPhase {
        /** Saved share, 0–100. */
        val percent: Int = percentOf(downloadedBytes, totalBytes)
    }

    /** It gave up for [reason]; the person retries. */
    data class Failed(
        val reason: DownloadFailureReason,
    ) : DownloadPhase

    /** Every file is on the device. */
    data object Downloaded : DownloadPhase
}

/**
 * The [DownloadPhase] of [status], with [wifiBlocked] true while Wi-Fi-only is on and the device has no unmetered
 * network. A download that is not moving and would move on Wi-Fi reads [DownloadPhase.WaitingForWifi]; one the person
 * paused stays [DownloadPhase.PausedByYou] whatever the network.
 */
fun downloadPhaseOf(
    status: BookDownloadStatus,
    wifiBlocked: Boolean,
): DownloadPhase =
    when (status) {
        is BookDownloadStatus.NotDownloaded -> {
            DownloadPhase.NotDownloaded
        }

        is BookDownloadStatus.Completed -> {
            DownloadPhase.Downloaded
        }

        is BookDownloadStatus.Failed -> {
            DownloadPhase.Failed(status.reason)
        }

        is BookDownloadStatus.InProgress -> {
            when {
                status.downloadingFiles > 0 -> DownloadPhase.Downloading(status.downloadedBytes, status.totalBytes)

                wifiBlocked -> DownloadPhase.WaitingForWifi

                // Picking up again after the app was closed mid-download: a wait, not a queue.
                status.resumingAfterClose -> DownloadPhase.WaitingToResume

                else -> DownloadPhase.Queued
            }
        }

        is BookDownloadStatus.Paused -> {
            when (status.reason) {
                DownloadPauseReason.PAUSED_BY_YOU, null -> {
                    DownloadPhase.PausedByYou(status.downloadedBytes, status.totalBytes)
                }

                DownloadPauseReason.NETWORK_LOST,
                DownloadPauseReason.INTERRUPTED,
                DownloadPauseReason.SIGN_IN_REQUIRED,
                DownloadPauseReason.APP_CLOSED,
                DownloadPauseReason.URL_EXPIRED,
                -> {
                    if (wifiBlocked) DownloadPhase.WaitingForWifi else DownloadPhase.WaitingToResume
                }
            }
        }
    }

/** Above this many saved bytes, cancelling a download asks first (decimal megabytes, as storage is sold). */
private const val CANCEL_CONFIRM_BYTES = 100L * 1_000_000L

/** Above this share of the book saved, cancelling a download asks first. */
private const val CANCEL_CONFIRM_DIVISOR = 4L

/**
 * Whether cancelling a download that has saved [downloadedBytes] of [totalBytes] should ask first: it would discard more
 * than about 100 MB, or more than a quarter of the book. Smaller discards cancel at once, since downloading them again
 * costs little.
 */
fun cancelNeedsConfirmation(
    downloadedBytes: Long,
    totalBytes: Long,
): Boolean =
    downloadedBytes > CANCEL_CONFIRM_BYTES ||
        (totalBytes > 0 && downloadedBytes * CANCEL_CONFIRM_DIVISOR > totalBytes)

/** The bytes cancelling [status]'s download would discard. */
fun BookDownloadStatus.discardableBytes(): Long =
    when (this) {
        is BookDownloadStatus.InProgress -> downloadedBytes
        is BookDownloadStatus.Paused -> downloadedBytes
        is BookDownloadStatus.Failed -> downloadedBytes
        is BookDownloadStatus.Completed, is BookDownloadStatus.NotDownloaded -> 0L
    }

/** The book's total size as the download knows it; 0 when unknown. */
fun BookDownloadStatus.knownTotalBytes(): Long =
    when (this) {
        is BookDownloadStatus.InProgress -> totalBytes
        is BookDownloadStatus.Paused -> totalBytes
        is BookDownloadStatus.Failed -> totalBytes
        is BookDownloadStatus.Completed -> totalBytes
        is BookDownloadStatus.NotDownloaded -> 0L
    }

private const val PERCENT = 100

private fun percentOf(
    done: Long,
    total: Long,
): Int = if (total > 0) (done * PERCENT / total).toInt().coerceIn(0, PERCENT) else 0
