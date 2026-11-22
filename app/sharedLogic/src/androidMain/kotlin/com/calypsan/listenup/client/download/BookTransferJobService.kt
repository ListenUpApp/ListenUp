package com.calypsan.listenup.client.download

import android.app.Notification
import android.app.job.JobParameters
import android.app.job.JobService
import android.os.Build
import androidx.annotation.RequiresApi
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * Android 14+: runs one book's user-initiated data transfer job ([UidtBookTransferScheduler]).
 *
 * The notification is posted the moment the job starts, before any network I/O, as a user-initiated job requires.
 * [JobParameters] is held for the life of the run: Android 16 stops a job whose parameters were dropped
 * (`STOP_REASON_TIMEOUT_ABANDONED`).
 *
 * A stop the system makes ([onStopJob]) cancels the run, which records why and keeps the partial; the job asks to be
 * rescheduled unless the app or the person stopped it.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class BookTransferJobService :
    JobService(),
    KoinComponent {
    private val transfers: BookTransfers by inject()

    /** The runs this service holds, by job id: each host keeps its job's parameters and coroutine alive. */
    private val running = ConcurrentHashMap<Int, JobHost>()

    override fun onStartJob(params: JobParameters): Boolean {
        val bookId = params.extras.getString(JOB_EXTRA_BOOK_ID) ?: return false
        val title = params.extras.getString(JOB_EXTRA_TITLE).orEmpty()
        val host = JobHost(params)
        host.postNow(transfers.startingNotification(bookId, title))
        running[params.jobId] = host
        host.job =
            transfers.scope.launch {
                try {
                    val outcome = transfers.runOn(bookId, host)
                    jobFinished(params, outcome == BookTransferOutcome.RETRY_LATER)
                } catch (e: CancellationException) {
                    // The system stopped the job (onStopJob already answered) or the app cancelled it.
                    throw e
                } catch (e: Exception) {
                    // Anything unexpected (a database error) waits and tries again rather than holding the job open.
                    logger.error(e) { "download: job failed book=$bookId" }
                    jobFinished(params, true)
                } finally {
                    running.remove(params.jobId, host)
                }
            }
        logger.info { "download: job start book=$bookId job=${params.jobId}" }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val code = params.stopReason
        running[params.jobId]?.let { host ->
            host.stopCode = code
            host.job?.cancel()
        }
        logger.info { "download: job stopped job=${params.jobId} code=$code" }
        return wantsRescheduleAfterStop(code)
    }

    /** One job's view of the platform: its notification, its byte count and its stop. */
    internal inner class JobHost(
        private val params: JobParameters,
    ) : PlatformTransferHost {
        override val transport = DownloadTransport.UIDT
        override val foreground = true

        @Volatile
        var stopCode: Int = JobParameters.STOP_REASON_UNDEFINED

        /** The run's coroutine, cancelled when the system stops the job. */
        @Volatile
        var job: Job? = null

        fun postNow(notification: Notification) {
            setNotification(
                params,
                transfers.activeNotificationId,
                notification,
                JOB_END_NOTIFICATION_POLICY_REMOVE,
            )
        }

        override suspend fun post(notification: Notification) = postNow(notification)

        override fun reportTransferred(bytes: Long) {
            updateTransferredNetworkBytes(params, bytes.coerceAtLeast(0L), 0L)
        }

        override fun platformStopCause(): StopCause = stopCauseOf(stopCode)
    }
}

/**
 * Whether a user-initiated job the system stopped with [stopCode] should be rescheduled: always, except when the app
 * cancelled it (a pause, a cancel, a rule change that schedules its own replacement) or the person stopped ListenUp
 * from the system, which they meant.
 */
internal fun wantsRescheduleAfterStop(stopCode: Int): Boolean =
    stopCode != JobParameters.STOP_REASON_CANCELLED_BY_APP && stopCode != JobParameters.STOP_REASON_USER
