package com.calypsan.listenup.client.download

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.annotation.RequiresApi

/** The JobScheduler namespace download jobs live in, so their ids never meet WorkManager's own. */
internal const val DOWNLOAD_JOB_NAMESPACE = "downloads"

/** Job extras: the book a job downloads, and its title for the notification shown before the database is read. */
internal const val JOB_EXTRA_BOOK_ID = "book_id"
internal const val JOB_EXTRA_TITLE = "title"

private const val BACKOFF_INITIAL_MS = 30_000L

/** A book's job id: stable across processes, so a job scheduled before a restart is the one cancelled after it. */
internal fun downloadJobIdFor(bookId: String): Int = bookId.hashCode()

/**
 * The user-initiated data transfer job that downloads [request]'s book (Android 14+): user-initiated, persisted across
 * reboots, on the download network ([downloadNetworkRequest]), with the bytes left as its estimate and a 30 s
 * exponential backoff, and held while storage is low rather than failing at a full disk.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
internal fun downloadJobInfo(
    context: Context,
    request: BookTransferRequest,
): JobInfo =
    JobInfo
        .Builder(downloadJobIdFor(request.bookId), ComponentName(context, BookTransferJobService::class.java))
        .setUserInitiated(true)
        .setRequiredNetwork(downloadNetworkRequest(request.wifiOnly))
        .setEstimatedNetworkBytes(request.remainingBytes.coerceAtLeast(0L), 0L)
        .setBackoffCriteria(BACKOFF_INITIAL_MS, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
        .setPersisted(true)
        .setRequiresStorageNotLow(true)
        .setExtras(
            PersistableBundle().apply {
                putString(JOB_EXTRA_BOOK_ID, request.bookId)
                putString(JOB_EXTRA_TITLE, request.title)
            },
        ).build()

/**
 * Android 14+: each book is a user-initiated data transfer job, the transport Google recommends for a download the
 * person started. It is exempt from standby-bucket quotas and needs no foreground service type.
 *
 * Such a job can only be scheduled while the app is visible; otherwise [JobScheduler.schedule] refuses it, which is
 * reported as [ScheduleResult.REFUSED] and retried at the next foreground.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
internal class UidtBookTransferScheduler(
    private val context: Context,
) : BookTransferScheduler {
    override val transport = DownloadTransport.UIDT

    private val jobs: JobScheduler?
        get() = context.getSystemService(JobScheduler::class.java)?.forNamespace(DOWNLOAD_JOB_NAMESPACE)

    override suspend fun schedule(
        request: BookTransferRequest,
        replace: Boolean,
    ): ScheduleResult {
        val scheduler = jobs ?: return ScheduleResult.REFUSED
        // Scheduling an id that is already scheduled replaces it, which also stops it if it is running. A plain
        // schedule of a book whose job already exists leaves that job alone; only a rule change replaces it.
        if (!replace &&
            scheduler.getPendingJob(downloadJobIdFor(request.bookId)) != null
        ) {
            return ScheduleResult.SCHEDULED
        }
        return if (scheduler.schedule(downloadJobInfo(context, request)) == JobScheduler.RESULT_SUCCESS) {
            ScheduleResult.SCHEDULED
        } else {
            ScheduleResult.REFUSED
        }
    }

    override suspend fun cancel(bookId: String) {
        jobs?.cancel(downloadJobIdFor(bookId))
    }

    override suspend fun isScheduled(bookId: String): Boolean = jobs?.getPendingJob(downloadJobIdFor(bookId)) != null
}
