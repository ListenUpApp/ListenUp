package com.calypsan.listenup.client.domain.model

/**
 * Aggregated download status for a book, modeled as a sealed hierarchy so consumers
 * can exhaustively match on the variant shape without illegal-state combinations.
 *
 * Replaces the legacy flat `data class BookDownloadStatus(state: BookDownloadState, ...)` per
 * W8 Phase B (W8 handoff design § "Refactor `BookDownloadStatus` from flat data class to sealed
 * hierarchy"). Closes the rubric drift around "UI state is a per-screen sealed hierarchy, not a
 * flat data class."
 */
sealed interface BookDownloadStatus {
    val bookId: String

    /** No download exists for this book (or all files were explicitly deleted). */
    data class NotDownloaded(
        override val bookId: String,
    ) : BookDownloadStatus

    /**
     * One or more files in this book are mid-flight (downloading, waiting for server, queued).
     *
     * @property waitingForServerFiles Count of files currently in `WAITING_FOR_SERVER` state. Zero
     * until Phase D adds the state. The field is on the type now so Phase C/D don't reshape the
     * sealed hierarchy mid-stream.
     */
    data class InProgress(
        override val bookId: String,
        val totalFiles: Int,
        val downloadingFiles: Int,
        val waitingForServerFiles: Int,
        val completedFiles: Int,
        val totalBytes: Long,
        val downloadedBytes: Long,
    ) : BookDownloadStatus {
        val progress: Float = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes else 0f
    }

    /** All files for this book are downloaded. */
    data class Completed(
        override val bookId: String,
        val totalBytes: Long,
    ) : BookDownloadStatus

    /**
     * One or more files failed and the worker exhausted its retries.
     *
     * @property partiallyDownloadedFiles Files that completed successfully before the failure;
     * lets the UI show "X of N files downloaded" alongside the retry CTA.
     */
    data class Failed(
        override val bookId: String,
        val errorMessage: String,
        val partiallyDownloadedFiles: Int,
    ) : BookDownloadStatus

    /** User cancelled or system paused the download. */
    data class Paused(
        override val bookId: String,
        val pausedFiles: Int,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : BookDownloadStatus
}
