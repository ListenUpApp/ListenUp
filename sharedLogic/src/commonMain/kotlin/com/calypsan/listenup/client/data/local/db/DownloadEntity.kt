package com.calypsan.listenup.client.data.local.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Tracks download state for individual audio files.
 *
 * Each audio file in a book gets its own entry, allowing:
 * - Partial downloads (some files complete, others pending)
 * - Resume support (tracks bytes downloaded)
 * - Per-file error tracking
 */
@Entity(
    tableName = "downloads",
    indices = [Index(value = ["bookId"])],
)
data class DownloadEntity(
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
)
