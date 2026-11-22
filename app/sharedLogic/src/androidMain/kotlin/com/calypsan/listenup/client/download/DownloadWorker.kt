package com.calypsan.listenup.client.download

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * The per-file worker of earlier releases, kept for one release so work WorkManager persisted before the update never
 * lands on a missing class. It downloads nothing: it succeeds at once, and the start of the app cancels the rest of
 * that work and schedules the book as one run ([BookTransfers.resumeAll]). Delete it in the release after this one.
 */
class DownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    /** The input keys earlier releases wrote. */
    companion object {
        /** The book the file belonged to. */
        const val KEY_BOOK_ID = "book_id"
    }

    override suspend fun doWork(): Result {
        logger.info { "download: retired per-file worker drained book=${inputData.getString(KEY_BOOK_ID) ?: "-"}" }
        return Result.success()
    }
}
