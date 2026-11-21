package com.calypsan.listenup.client.download

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.await
import androidx.work.workDataOf
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * WorkManager tag the per-file workers of earlier releases carried, one per book. Kept for one release so the start
 * of the app can cancel those workers; nothing enqueues under it any more.
 */
internal fun legacyBookTag(bookIdValue: String): String = "download_$bookIdValue"

/** Unique-work name for a book's run. */
internal fun bookWorkName(bookIdValue: String): String = "download_book_$bookIdValue"

/** Tag on every book run, so they can be found as a group. */
internal const val BOOK_TRANSFER_TAG = "book_transfer"

/**
 * The network constraint for a download: the same network rule the Android 14+ job uses ([downloadNetworkRequest]),
 * so both transports agree that a LAN-only server qualifies. `UNMETERED` is Wi-Fi/ethernet only; `CONNECTED` is any.
 */
internal fun downloadNetworkConstraints(wifiOnly: Boolean): Constraints =
    Constraints
        .Builder()
        .setRequiredNetworkRequest(
            downloadNetworkRequest(wifiOnly),
            if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED,
        ).build()

/**
 * The work request that runs [request]'s book on WorkManager (Android 11–13).
 *
 * Backoff is linear from WorkManager's minimum rather than its exponential default. WorkManager advances the attempt
 * count for every run the system stopped as well as every retry, and a lost connection retries rather than fails;
 * exponential backoff on that inflated count would leave a download that has been stopped a few times waiting hours
 * after the network came back.
 */
internal fun buildBookTransferRequest(request: BookTransferRequest): OneTimeWorkRequest =
    OneTimeWorkRequestBuilder<BookTransferWorker>()
        .setBackoffCriteria(BackoffPolicy.LINEAR, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
        .setInputData(
            workDataOf(
                BookTransferWorker.KEY_BOOK_ID to request.bookId,
                BookTransferWorker.KEY_TITLE to request.title,
            ),
        ).setConstraints(downloadNetworkConstraints(request.wifiOnly))
        .addTag(BOOK_TRANSFER_TAG)
        .build()

/**
 * Android 11–13: each book is a unique WorkManager worker that promotes itself to a `dataSync` foreground service,
 * the documented fallback for user-initiated data transfer jobs below Android 14.
 */
internal class WorkManagerBookTransferScheduler(
    private val workManager: WorkManager,
) : BookTransferScheduler {
    override val transport = DownloadTransport.WORKMANAGER_FGS

    override suspend fun schedule(
        request: BookTransferRequest,
        replace: Boolean,
    ): ScheduleResult {
        // KEEP never displaces a run already scheduled or running; REPLACE (a network-rule change) cancels it, which
        // pauses the file and keeps its partial, and the replacement resumes from those bytes.
        workManager
            .enqueueUniqueWork(
                bookWorkName(request.bookId),
                if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                buildBookTransferRequest(request),
            ).await()
        return ScheduleResult.SCHEDULED
    }

    override suspend fun cancel(bookId: String) {
        workManager.cancelUniqueWork(bookWorkName(bookId)).await()
    }

    override suspend fun isScheduled(bookId: String): Boolean =
        workManager.getWorkInfosForUniqueWorkFlow(bookWorkName(bookId)).first().any { !it.state.isFinished }
}
