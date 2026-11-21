package com.calypsan.listenup.client.download

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException

private val logger = KotlinLogging.logger {}

/**
 * Android 11–13: downloads one book as a WorkManager worker that promotes itself to a `dataSync` foreground service,
 * so it is not held to a plain worker's ten minutes and is not what the system or an OEM killer stops first.
 *
 * On Android 12–13 a worker that starts while the app is in the background (a constraint met again, a retry after
 * backoff) cannot start a foreground service; it carries on as a plain worker, recorded as
 * [DownloadTransport.WORKMANAGER_PLAIN]. The download never fails for it.
 */
class BookTransferWorker internal constructor(
    context: Context,
    params: WorkerParameters,
    private val transfers: BookRunner,
) : CoroutineWorker(context, params) {
    /** The worker WorkManager constructs, running books through [transfers]. */
    constructor(
        context: Context,
        params: WorkerParameters,
        transfers: BookTransfers,
    ) : this(context, params, transfers.asRunner())

    /** Input keys. */
    companion object {
        /** The book to download. */
        const val KEY_BOOK_ID = "book_id"

        /** Its title, for the notification shown before the database is read. */
        const val KEY_TITLE = "title"
    }

    override suspend fun doWork(): Result {
        val bookId = inputData.getString(KEY_BOOK_ID) ?: return Result.failure()
        val title = inputData.getString(KEY_TITLE).orEmpty()
        val foreground = promote(transfers.startingNotification(bookId, title))
        val host = WorkerHost(foreground)
        logger.info { "download: worker start book=$bookId fg=$foreground attempt=$runAttemptCount" }
        val outcome =
            try {
                transfers.runOn(bookId, host)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Anything unexpected (a database error) waits and tries again rather than failing the download.
                logger.error(e) { "download: worker failed book=$bookId" }
                BookTransferOutcome.RETRY_LATER
            }
        return when (outcome) {
            BookTransferOutcome.COMPLETED -> Result.success()
            BookTransferOutcome.RETRY_LATER -> Result.retry()
            BookTransferOutcome.GAVE_UP -> Result.failure()
        }
    }

    /** Become a `dataSync` foreground service. False when the platform refuses (a background start on 12–13). */
    private suspend fun promote(notification: Notification): Boolean =
        try {
            setForeground(foregroundInfo(notification))
            true
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (an IllegalStateException) on Android 12–13.
            logger.info {
                "download: no foreground for this run (${e::class.simpleName}); continuing as a plain worker"
            }
            false
        }

    private fun foregroundInfo(notification: Notification) =
        ForegroundInfo(transfers.activeNotificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    private inner class WorkerHost(
        override val foreground: Boolean,
    ) : PlatformTransferHost {
        override val transport: DownloadTransport =
            if (foreground) DownloadTransport.WORKMANAGER_FGS else DownloadTransport.WORKMANAGER_PLAIN

        override suspend fun post(notification: Notification) {
            if (foreground) setForeground(foregroundInfo(notification)) else transfers.postActive(notification)
        }

        override fun platformStopCause(): StopCause =
            if (Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.S
            ) {
                stopCauseOf(stopReason)
            } else {
                STOP_CAUSE_UNAVAILABLE_ON_API_30
            }

        override fun finished() {
            if (!foreground) transfers.cancelActive()
        }
    }
}
