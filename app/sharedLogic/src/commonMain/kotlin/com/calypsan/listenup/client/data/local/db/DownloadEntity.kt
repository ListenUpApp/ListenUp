package com.calypsan.listenup.client.data.local.db

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.calypsan.listenup.client.domain.model.DownloadPauseReason

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
)
