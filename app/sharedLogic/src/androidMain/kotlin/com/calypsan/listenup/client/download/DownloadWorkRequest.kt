package com.calypsan.listenup.client.download

import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.workDataOf
import com.calypsan.listenup.client.data.local.db.DownloadEntity

// The single place a download OneTimeWorkRequest is built.
//
// Three near-identical copies of this block used to live in DownloadManager.downloadBook,
// DownloadManager.resumeIncompleteDownloads, and AndroidDownloadEnqueuer — and the third
// quietly used a different ExistingWorkPolicy than the other two. One builder means the
// constraint, the input data, and the tags cannot drift apart again.

/** WorkManager tag covering every file of one book — the unit `cancelAllWorkByTag` cancels. */
internal fun bookTag(bookIdValue: String): String = "download_$bookIdValue"

/** WorkManager tag for a single file, so one file can be cancelled without its siblings. */
internal fun fileCancelTag(audioFileId: String): String = "download_file_$audioFileId"

/**
 * Unique-work name for a single file's download.
 *
 * Deliberately a DIFFERENT string from [fileCancelTag]: the unique-work name and the cancel tag
 * address different WorkManager concepts and must not be consolidated.
 */
internal fun fileWorkName(audioFileId: String): String = "download_$audioFileId"

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

/** Builds the work request that downloads [entity]'s file under the current [wifiOnly] policy. */
internal fun buildDownloadRequest(
    entity: DownloadEntity,
    wifiOnly: Boolean,
): OneTimeWorkRequest =
    OneTimeWorkRequestBuilder<DownloadWorker>()
        .setInputData(
            workDataOf(
                DownloadWorker.KEY_AUDIO_FILE_ID to entity.audioFileId,
                DownloadWorker.KEY_BOOK_ID to entity.bookId,
                DownloadWorker.KEY_FILENAME to entity.filename,
                DownloadWorker.KEY_FILE_SIZE to entity.totalBytes,
            ),
        ).setConstraints(downloadNetworkConstraints(wifiOnly))
        .addTag(bookTag(entity.bookId))
        .addTag(fileCancelTag(entity.audioFileId))
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
