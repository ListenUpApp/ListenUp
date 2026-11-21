package com.calypsan.listenup.client.download

import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.DownloadError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.MAX_DOWNLOAD_RETRIES
import com.calypsan.listenup.client.domain.model.DownloadPauseReason
import com.calypsan.listenup.client.domain.repository.DownloadRepository
import com.calypsan.listenup.core.error.ErrorBus
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/** Whether a failed download run should be scheduled again. */
internal enum class DownloadFailureVerdict {
    /** Run it again later; the platform's network constraint and backoff decide when. */
    RETRY_LATER,

    /** Stop here; the row already says why. */
    GIVE_UP,
}

/**
 * Record what a failed download run means on its row, and say whether to run it again.
 *
 * - **An ended session** pauses until sign-in, spending nothing. The global auth observer reacts to
 *   the same error on the foreground bus; a background run only gives up quietly.
 * - **A full disk** fails, tells the person, and is not retried: retrying cannot make room.
 * - **A lost connection** (Doze cutting the network, a Wi-Fi drop, a read timing out) is a wait,
 *   not a failure. It pauses with [DownloadPauseReason.NETWORK_LOST], spends no retry and raises no
 *   error, and the run is scheduled again for when a network is back.
 * - **Anything else** is a genuine failure: FAILED, one retry spent, and the person told. It runs
 *   again while the row's own count is under [MAX_DOWNLOAD_RETRIES]. The count lives on the row, not
 *   in the platform's attempt counter, because a run the system stopped counts as an attempt there.
 *
 * A full disk is told by its type, [DownloadError.InsufficientStorage], which [downloadAudioFile]
 * raises where the bytes are written. Never by reading [AppError.debugInfo]: that is the original
 * exception text, and a lost connection's names the host, which on a self-hosted box can be
 * `storage.lan`.
 */
internal suspend fun settleDownloadFailure(
    audioFileId: String,
    error: AppError,
    repository: DownloadRepository,
    errorBus: ErrorBus,
): DownloadFailureVerdict =
    when {
        error is AuthError.SessionExpired -> {
            logger.warn { "download: paused file=$audioFileId reason=${DownloadPauseReason.SIGN_IN_REQUIRED}" }
            pause(repository, audioFileId, DownloadPauseReason.SIGN_IN_REQUIRED)
            DownloadFailureVerdict.GIVE_UP
        }

        error is DownloadError.InsufficientStorage -> {
            errorBus.emit(error)
            logger.error { "download: failed file=$audioFileId error=${error.code}" }
            val _ = fail(repository, audioFileId, error)
            DownloadFailureVerdict.GIVE_UP
        }

        error is TransportError.NetworkUnavailable || error is TransportError.Timeout -> {
            logger.info {
                "download: paused file=$audioFileId reason=${DownloadPauseReason.NETWORK_LOST} error=${error.code}"
            }
            pause(repository, audioFileId, DownloadPauseReason.NETWORK_LOST)
            DownloadFailureVerdict.RETRY_LATER
        }

        else -> {
            val downloadError = DownloadError.DownloadFailed(debugInfo = error.debugInfo)
            errorBus.emit(downloadError)
            val failures =
                fail(repository = repository, audioFileId = audioFileId, error = downloadError, causeCode = error.code)
            logger.error {
                "download: failed file=$audioFileId error=${error.code} failures=$failures/$MAX_DOWNLOAD_RETRIES"
            }
            if (failures < MAX_DOWNLOAD_RETRIES) DownloadFailureVerdict.RETRY_LATER else DownloadFailureVerdict.GIVE_UP
        }
    }

private suspend fun pause(
    repository: DownloadRepository,
    audioFileId: String,
    reason: DownloadPauseReason,
) {
    if (repository.markPaused(audioFileId, reason) is AppResult.Failure) {
        logger.warn { "download: could not persist the pause of file=$audioFileId" }
    }
}

/**
 * Mark the row FAILED and return the failures it has now recorded. A write that does not land is
 * read as the budget spent, so a row the database cannot update is never retried without limit.
 */
private suspend fun fail(
    repository: DownloadRepository,
    audioFileId: String,
    error: DownloadError,
    causeCode: String? = null,
): Int =
    when (val recorded = repository.markFailed(audioFileId, error, causeCode)) {
        is AppResult.Success -> {
            recorded.data
        }

        is AppResult.Failure -> {
            logger.warn { "download: could not persist the failure of file=$audioFileId" }
            MAX_DOWNLOAD_RETRIES
        }
    }
