package com.calypsan.listenup.client.domain.model

/**
 * Summary of a book the user has fully downloaded, suitable for storage-management UIs.
 *
 * One instance per book (not per file) — files are aggregated into [fileCount] and [sizeBytes].
 * A held book stays listed — hiding bytes on disk would strand them — and is marked [isHeld].
 */
data class DownloadedBookSummary(
    val bookId: String,
    val title: String,
    val authorNames: String,
    val sizeBytes: Long,
    val fileCount: Int,
    /**
     * Held for review in the admin inbox. Its download stays on the device, but it can't be played
     * until it is released (spec §8–§9), so Downloads marks it and says so. Joined from the one held
     * set by the presenter ([com.calypsan.listenup.client.presentation.storage.StorageViewModel]);
     * the repository reports what is on disk and leaves this false.
     */
    val isHeld: Boolean = false,
)
