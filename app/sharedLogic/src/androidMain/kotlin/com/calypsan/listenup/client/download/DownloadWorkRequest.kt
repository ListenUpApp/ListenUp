package com.calypsan.listenup.client.download

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkRequest
import androidx.work.workDataOf
import com.calypsan.listenup.client.data.local.db.DownloadEntity
import java.util.concurrent.TimeUnit

// The single place a download OneTimeWorkRequest is built.
//
// Three near-identical copies of this block used to live in DownloadManager.downloadBook,
// DownloadManager.resumeIncompleteDownloads, and AndroidDownloadEnqueuer — and the third
// quietly used a different ExistingWorkPolicy than the other two. One builder means the
// constraint, the input data, and the tags cannot drift apart again.

/** WorkManager tag covering every file of one book — the unit `cancelAllWorkByTag` cancels. */
internal fun bookTag(bookIdValue: String): String = "download_$bookIdValue"

/** Unique-work name for a single file's download. */
internal fun fileWorkName(audioFileId: String): String = "download_$audioFileId"

/**
 * The incomplete [rows] recovery should reset and re-enqueue, leaving out those whose worker is
 * already running ([runningAudioFileIds]).
 *
 * A running worker writes DOWNLOADING once, at its start, so demoting its row to QUEUED would make a
 * live download read "Queued" for the rest of the file. Mirrors the in-flight filter in
 * `AppleDownloadService.resumeIncompleteDownloads`.
 */
internal fun rowsToRequeue(
    rows: List<DownloadEntity>,
    runningAudioFileIds: Set<String>,
): List<DownloadEntity> = rows.filterNot { it.audioFileId in runningAudioFileIds }

/**
 * Network constraint for a download, from the live Wi-Fi-only preference.
 *
 * `UNMETERED` is Wi-Fi/ethernet only; `CONNECTED` is any network including cellular.
 */
internal fun downloadNetworkConstraints(wifiOnly: Boolean): Constraints =
    Constraints
        .Builder()
        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .build()

/**
 * Builds the work request that downloads [entity]'s file under the current [wifiOnly] policy.
 *
 * Backoff is linear from WorkManager's minimum rather than its exponential default. WorkManager
 * advances the attempt count for every run the system stopped as well as every retry, and a lost
 * connection retries rather than fails; exponential backoff on that inflated count would leave a
 * download that has been stopped a few times waiting hours after the network came back.
 */
internal fun buildDownloadRequest(
    entity: DownloadEntity,
    wifiOnly: Boolean,
): OneTimeWorkRequest =
    OneTimeWorkRequestBuilder<DownloadWorker>()
        .setBackoffCriteria(BackoffPolicy.LINEAR, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
        .setInputData(
            workDataOf(
                DownloadWorker.KEY_AUDIO_FILE_ID to entity.audioFileId,
                DownloadWorker.KEY_BOOK_ID to entity.bookId,
                DownloadWorker.KEY_FILENAME to entity.filename,
                DownloadWorker.KEY_FILE_SIZE to entity.totalBytes,
            ),
        ).setConstraints(downloadNetworkConstraints(wifiOnly))
        .addTag(bookTag(entity.bookId))
        .build()

/**
 * The unique-work name / request pairs needed to bring [rows] onto the [wifiOnly] policy.
 *
 * The constraint comes from the argument, never from the row: a row carries no record of the
 * policy it was enqueued under, which is exactly why the preference used to be decorative.
 */
internal fun constraintRefreshWork(
    rows: List<DownloadEntity>,
    wifiOnly: Boolean,
): List<Pair<String, OneTimeWorkRequest>> =
    rows.map { row -> fileWorkName(row.audioFileId) to buildDownloadRequest(row, wifiOnly) }
