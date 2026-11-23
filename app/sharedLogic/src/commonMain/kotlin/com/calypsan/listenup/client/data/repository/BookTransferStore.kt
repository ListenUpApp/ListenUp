package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.data.local.db.DownloadDao
import com.calypsan.listenup.client.data.local.db.DownloadEntity
import com.calypsan.listenup.client.data.local.db.DownloadStopEventDao
import com.calypsan.listenup.client.data.local.db.DownloadStopEventEntity
import com.calypsan.listenup.client.data.local.db.MAX_DOWNLOAD_RETRIES
import com.calypsan.listenup.client.data.local.db.MAX_DOWNLOAD_STOP_EVENTS
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.download.DownloadStop

/**
 * The download rows as a book run sees them: which file to transfer next, claiming it, and recording why a run
 * stopped. The writes a run, a pause and a cancel make go through here (and through
 * [com.calypsan.listenup.client.domain.repository.DownloadRepository] for progress, completion and failure), so no
 * transfer code touches the DAO.
 */
internal class BookTransferStore(
    private val downloadDao: DownloadDao,
    private val stopEventDao: DownloadStopEventDao,
    private val transactionRunner: TransactionRunner,
) {
    /** The next file of [bookId] a run should transfer, or null when the book has nothing left to transfer. */
    suspend fun nextForRun(bookId: String): DownloadEntity? = downloadDao.nextForRun(bookId, MAX_DOWNLOAD_RETRIES)

    /** Claim [audioFileId] for transfer. False when the row moved on (paused, cancelled, completed) since it was read. */
    suspend fun claim(
        audioFileId: String,
        startedAt: Long,
    ): Boolean = downloadDao.claimForRun(audioFileId, startedAt, MAX_DOWNLOAD_RETRIES) > 0

    /** Every row of [bookId], in file order. */
    suspend fun rowsFor(bookId: String): List<DownloadEntity> = downloadDao.getForBook(bookId)

    /** The books with something a run could still transfer, in the order they were queued. */
    suspend fun runnableBooks(): List<String> = downloadDao.booksWithRunnableFiles(MAX_DOWNLOAD_RETRIES)

    /** How many other books are queued or downloading behind [bookId]. */
    suspend fun otherActiveBooks(bookId: String): Int = downloadDao.countOtherActiveBooks(bookId)

    /** Pause every unfinished file of [bookId] for the person. Returns how many files it paused. */
    suspend fun pauseForUser(bookId: String): Int = downloadDao.pauseBookForUser(bookId)

    /** Forget every file of [bookId] (the caller removes them from disk). */
    suspend fun discard(bookId: String) = downloadDao.discardBook(bookId)

    /**
     * Pause every DOWNLOADING file not in [excluding] (the files this process is transferring) to resume from its
     * partial: the process that was transferring it died. Returns the rows it released, as they were.
     */
    suspend fun releaseOrphans(excluding: Set<String>): List<DownloadEntity> =
        downloadDao
            .getDownloading()
            .filter { row -> row.audioFileId !in excluding && downloadDao.releaseOrphan(row.audioFileId) > 0 }

    /**
     * Record [stop]: on its file's row (reason, code, time, count, transport) when it names one, and in the bounded
     * history, pruned to the newest [MAX_DOWNLOAD_STOP_EVENTS], all in one transaction.
     */
    suspend fun recordStop(stop: DownloadStop) {
        transactionRunner.atomically {
            stop.audioFileId?.let { audioFileId ->
                downloadDao.recordStop(
                    audioFileId = audioFileId,
                    reason = stop.cause.reason,
                    code = stop.cause.code,
                    at = stop.at,
                    transport = stop.transport,
                )
            }
            stopEventDao.insertStopEvent(stop.toEntity())
            stopEventDao.pruneStopEvents(MAX_DOWNLOAD_STOP_EVENTS)
        }
    }

    /** The newest [limit] stops, newest first. */
    suspend fun recentStops(limit: Int = MAX_DOWNLOAD_STOP_EVENTS): List<DownloadStopEventEntity> =
        stopEventDao.recentStopEvents(limit)
}

private fun DownloadStop.toEntity() =
    DownloadStopEventEntity(
        at = at,
        bookId = bookId,
        audioFileId = audioFileId,
        reason = cause.reason,
        reasonCode = cause.code,
        bytesAtStop = bytesAtStop,
        totalBytes = totalBytes,
        transport = transport,
        foreground = foreground,
        sdkInt = device.sdkInt,
        standbyBucket = device.standbyBucket,
        powerSave = device.powerSave,
        deviceIdle = device.deviceIdle,
        ignoringBatteryOptimizations = device.ignoringBatteryOptimizations,
        notificationsEnabled = device.notificationsEnabled,
        wifiOnly = device.wifiOnly,
        networkMetered = device.networkMetered,
    )
