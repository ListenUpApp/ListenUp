package com.calypsan.listenup.client.domain.model

/**
 * Summary of a book the user has fully downloaded, suitable for storage-management UIs.
 *
 * One instance per book (not per file) — files are aggregated into [fileCount] and [sizeBytes].
 */
data class DownloadedBookSummary(
    val bookId: String,
    val title: String,
    val authorNames: String,
    val coverBlurHash: String?,
    val sizeBytes: Long,
    val fileCount: Int,
)
