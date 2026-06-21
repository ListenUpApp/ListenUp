package com.calypsan.listenup.client.domain.model

/**
 * Domain representation of a single audio-file download row.
 *
 * Mirrors the consumer-relevant fields exposed by [com.calypsan.listenup.client.data.local.db.DownloadEntity] in the data layer
 * without leaking that entity type across the [com.calypsan.listenup.client.domain.repository.DownloadRepository]
 * boundary. Mapping happens in [com.calypsan.listenup.client.data.repository.DownloadRepositoryImpl].
 *
 * @property audioFileId Stable identifier for the audio file; matches the primary key in the DB row.
 * @property bookId The book this audio file belongs to.
 * @property filename Original filename on disk.
 * @property fileIndex Zero-based order of this file within the book.
 * @property status Current lifecycle status of this download.
 * @property localPath Absolute local path once the download is complete; null while in-flight.
 * @property totalBytes Expected total size in bytes (0 if not yet known).
 * @property downloadedBytes Bytes received so far.
 * @property queuedAt Epoch-ms timestamp when the download was enqueued.
 * @property startedAt Epoch-ms timestamp when the download began, or null if not yet started.
 * @property completedAt Epoch-ms timestamp when the download finished, or null if not complete.
 * @property errorMessage Human-readable error detail, populated when [status] is [DownloadStatus.FAILED].
 * @property retryCount Number of times this download has been retried after failure.
 */
data class Download(
    val audioFileId: String,
    val bookId: String,
    val filename: String,
    val fileIndex: Int,
    val status: DownloadStatus,
    val localPath: String?,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val queuedAt: Long,
    val startedAt: Long?,
    val completedAt: Long?,
    val errorMessage: String?,
    val retryCount: Int = 0,
)

/**
 * Lifecycle states for an individual audio-file download.
 *
 * Mirrors [com.calypsan.listenup.client.data.local.db.DownloadState] at the domain layer so the
 * repository interface is free of persistence types.
 */
enum class DownloadStatus {
    /** Waiting to start. */
    QUEUED,

    /** Download is in progress. */
    DOWNLOADING,

    /** User paused or the system interrupted the download. */
    PAUSED,

    /** Successfully downloaded; [Download.localPath] is valid. */
    COMPLETED,

    /** An error occurred; see [Download.errorMessage] for detail. */
    FAILED,

    /** User explicitly deleted the files; suppress auto-download on next playback. */
    DELETED,

    /** User cancelled; distinct from [PAUSED] so resume sweeps skip this row. */
    CANCELLED,
}
