package com.calypsan.listenup.client.domain.model

/**
 * A book whose download has started and not finished — downloading, queued, paused or failed — for
 * the Downloads list, which shows these above the finished ones so a stopped download is never out
 * of sight.
 */
data class InProgressBookSummary(
    val bookId: String,
    val title: String,
    val authorNames: String,
    /** The book's local cover file, when one is on disk; null lets the cover resolve from the server. */
    val coverPath: String? = null,
    /** The cover's content hash, folded into the image cache key so a replaced cover shows. */
    val coverHash: String? = null,
    /** Where the download stands: [BookDownloadStatus.InProgress], [BookDownloadStatus.Paused] or [BookDownloadStatus.Failed]. */
    val status: BookDownloadStatus,
)
