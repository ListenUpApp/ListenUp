package com.calypsan.listenup.client.download

import com.calypsan.listenup.client.data.local.db.AudioFileEntity
import com.calypsan.listenup.client.data.local.db.DownloadEntity
import com.calypsan.listenup.client.data.local.db.DownloadState

/**
 * The QUEUED rows that (re)start the download of [toDownload], the files of [bookId] that are not
 * already complete or in flight. [audioFiles] is the whole book, in order, and gives each file its
 * position.
 *
 * A file paused or failed ([existing]) keeps the bytes it has: its partial survives a pause or a
 * failure and the transfer resumes from it, so zeroing the row would make the progress lie until the
 * next write. A file cancelled or removed starts from zero: its partial went with it, and carrying its
 * count forward made a fresh download read almost finished. Everything else starts afresh too,
 * including the retry budget, because the person has just asked for the download again. Its stop
 * history (why and how often it stopped) is kept.
 */
internal fun queuedDownloadRows(
    bookId: String,
    audioFiles: List<AudioFileEntity>,
    toDownload: List<AudioFileEntity>,
    existing: List<DownloadEntity>,
    queuedAt: Long,
): List<DownloadEntity> {
    val before = existing.associateBy { it.audioFileId }
    return toDownload.map { file ->
        val prior = before[file.id]
        DownloadEntity(
            audioFileId = file.id,
            bookId = bookId,
            filename = file.filename,
            fileIndex = audioFiles.indexOfFirst { it.id == file.id },
            state = DownloadState.QUEUED,
            localPath = null,
            totalBytes = file.size,
            downloadedBytes = prior?.takeIf { it.keepsPartial() }?.downloadedBytes ?: 0L,
            queuedAt = queuedAt,
            startedAt = null,
            completedAt = null,
            errorMessage = null,
            retryCount = 0,
            // The stop history is the file's own, not the attempt's: a Resume keeps it.
            stopReason = prior?.stopReason,
            stopReasonCode = prior?.stopReasonCode,
            stoppedAt = prior?.stoppedAt,
            stopCount = prior?.stopCount ?: 0,
            transport = prior?.transport,
        )
    }
}

/** Whether this row's partial is still on the device: a pause or a failure keeps it, a cancel or a removal does not. */
private fun DownloadEntity.keepsPartial(): Boolean = state == DownloadState.PAUSED || state == DownloadState.FAILED
