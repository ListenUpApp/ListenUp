package com.calypsan.listenup.client.data.local.db

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.calypsan.listenup.client.domain.model.DownloadPauseReason
import com.calypsan.listenup.client.download.DownloadStopReason
import com.calypsan.listenup.client.download.DownloadTransport

/**
 * Tracks download state for individual audio files.
 *
 * Each audio file in a book gets its own entry, allowing:
 * - Partial downloads (some files complete, others pending)
 * - Resume support (tracks bytes downloaded)
 * - Per-file error tracking
 *
 * [retryCount] counts genuine failures only; a run the system stopped never spends it. [pauseReason]
 * says why a PAUSED row is waiting and means nothing in any other state.
 *
 * The stop fields record the latest time a run stopped before the file finished: [stopReason] (with the platform's
 * raw [stopReasonCode] where it gave one), [stoppedAt], and [stopCount] across the row's life. [lastErrorCode] is the
 * [com.calypsan.listenup.api.error.AppError.code] of the latest genuine failure, which says why a FAILED row gave
 * up. [transport] is what last ran it. The full history is in [DownloadStopEventEntity].
 */
@Entity(
    tableName = "downloads",
    indices = [Index(value = ["bookId"])],
)
internal data class DownloadEntity(
    @PrimaryKey
    val audioFileId: String,
    val bookId: String,
    val filename: String,
    val fileIndex: Int,
    val state: DownloadState,
    val localPath: String?,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val queuedAt: Long,
    val startedAt: Long?,
    val completedAt: Long?,
    val errorMessage: String?,
    val retryCount: Int = 0,
    val pauseReason: DownloadPauseReason? = null,
    val stopReason: DownloadStopReason? = null,
    val stopReasonCode: Int? = null,
    val stoppedAt: Long? = null,
    @ColumnInfo(defaultValue = "0")
    val stopCount: Int = 0,
    val lastErrorCode: String? = null,
    val transport: DownloadTransport? = null,
)
