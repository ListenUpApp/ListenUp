package com.calypsan.listenup.client.download

import com.calypsan.listenup.client.data.local.db.AudioFileEntity
import com.calypsan.listenup.client.data.local.db.DownloadEntity
import com.calypsan.listenup.client.data.local.db.DownloadState

/**
 * The QUEUED rows that (re)start the download of [toDownload], the files of [bookId] that are not
 * already complete or in flight. [audioFiles] is the whole book, in order, and gives each file its
 * position.
 *
 * A file that already has a row ([existing]) keeps the bytes it has: its `.tmp` partial survives a
 * pause or a failure and the transfer resumes from it, so zeroing the row would make the progress
 * lie until the next write. Everything else starts afresh, including the retry budget, because the
 * person has just asked for the download again.
 */
internal fun queuedDownloadRows(
    bookId: String,
    audioFiles: List<AudioFileEntity>,
    toDownload: List<AudioFileEntity>,
    existing: List<DownloadEntity>,
    queuedAt: Long,
): List<DownloadEntity> {
    val bytesSoFar = existing.associate { it.audioFileId to it.downloadedBytes }
    return toDownload.map { file ->
        DownloadEntity(
            audioFileId = file.id,
            bookId = bookId,
            filename = file.filename,
            fileIndex = audioFiles.indexOfFirst { it.id == file.id },
            state = DownloadState.QUEUED,
            localPath = null,
            totalBytes = file.size,
            downloadedBytes = bytesSoFar[file.id] ?: 0L,
            queuedAt = queuedAt,
            startedAt = null,
            completedAt = null,
            errorMessage = null,
            retryCount = 0,
        )
    }
}
