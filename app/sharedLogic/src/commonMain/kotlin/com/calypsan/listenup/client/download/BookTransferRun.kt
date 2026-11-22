package com.calypsan.listenup.client.download

import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.onFailure
import com.calypsan.listenup.client.core.suspendRunCatching
import com.calypsan.listenup.client.data.local.db.DownloadEntity
import com.calypsan.listenup.client.data.local.db.DownloadState
import com.calypsan.listenup.client.data.repository.BookTransferStore
import com.calypsan.listenup.client.domain.model.DownloadPauseReason
import com.calypsan.listenup.client.domain.repository.DownloadRepository
import com.calypsan.listenup.core.currentEpochMilliseconds
import com.calypsan.listenup.core.error.ErrorBus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

private val logger = KotlinLogging.logger {}

/** How a book run ended, which its host turns into "done", "run me again later" or "stop here". */
internal enum class BookTransferOutcome {
    /** Every file the run could transfer is on the device. */
    COMPLETED,

    /** A file waits on the network or failed with retries left; the host schedules the run again with backoff. */
    RETRY_LATER,

    /** A file failed for good (or waits for sign-in); the row says why, and nothing schedules the run again. */
    GAVE_UP,
}

/**
 * Where a book run is, for its notification: [downloadedBytes] of [totalBytes] across the whole book, the file it is
 * on ([fileNumber] of [fileCount]), and whether it is still [resolving] the file's address (no bytes moving yet).
 */
internal data class BookTransferProgress(
    val bookId: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val fileNumber: Int,
    val fileCount: Int,
    val resolving: Boolean,
)

/** What runs a book: the platform's job or worker, seen by [BookTransferRun]. */
internal interface BookTransferHost {
    /** The transport, recorded with every stop. */
    val transport: DownloadTransport

    /** Whether the run holds a foreground (or user-visible) state; recorded with every stop. */
    val foreground: Boolean

    /** It is this run's turn; called before any network I/O, so the host can show what is downloading. */
    suspend fun onTurn() {}

    /** The run moved; called at each file's start and as its bytes arrive. */
    suspend fun onProgress(progress: BookTransferProgress)

    /** Why the platform stopped the run. Read once, while the stop is recorded. */
    fun platformStopCause(): StopCause
}

/**
 * Downloads one book, a file at a time in file order, on whatever host the platform gives it. The shared core of the
 * Android user-initiated job and the WorkManager worker: claiming, transferring, settling failures and recording stops
 * happen here, once, so the two transports cannot drift apart.
 *
 * - Files are claimed through [BookTransferStore.claim], so a file is never transferred twice and a file the person
 *   paused or cancelled is never started.
 * - The run finishes only when [BookTransferRegistry.nextOrFinish] finds nothing left under the lock scheduling
 *   takes, so a file queued while it finishes is picked up rather than stranded.
 * - A failure is settled by [settleDownloadFailure]: a lost connection waits without spending a retry, a genuine
 *   failure spends one, and either way the run ends so its host can back off.
 * - A stop (the platform cancelling the run) pauses the file and keeps its partial, records the stop on the row and in
 *   the history with the reason the app or the platform gave, and is re-thrown.
 */
@Suppress("LongParameterList")
internal class BookTransferRun(
    private val store: BookTransferStore,
    private val repository: DownloadRepository,
    private val fileManager: DownloadFileManager,
    private val downloader: AudioFileDownloader,
    private val registry: BookTransferRegistry,
    private val errorBus: ErrorBus,
    private val deviceState: DownloadDeviceState,
    private val now: () -> Long = ::currentEpochMilliseconds,
) {
    /** Download [bookId] on [host]. Cancellation (the host being stopped) is recorded and re-thrown. */
    suspend fun run(
        bookId: String,
        host: BookTransferHost,
    ): BookTransferOutcome {
        val token = registry.register(bookId)
        try {
            return registry.withTurn {
                host.onTurn()
                transferAll(token, host)
            }
        } finally {
            withContext(NonCancellable) { registry.unregister(token) }
        }
    }

    private suspend fun transferAll(
        token: BookTransferRegistry.Token,
        host: BookTransferHost,
    ): BookTransferOutcome {
        while (true) {
            val row = registry.nextOrFinish(token) { store.nextForRun(token.bookId) } ?: break
            if (!store.claim(row.audioFileId, now())) continue
            val outcome = transferFile(token, row, host)
            if (outcome != null) return outcome
        }
        logger.info { "download: book complete book=${token.bookId}" }
        return BookTransferOutcome.COMPLETED
    }

    /**
     * Transfer one claimed file. Null when it landed and the run goes on to the next. A stop is re-thrown, after it is
     * recorded under NonCancellable, which must land first.
     */
    @Suppress("SuspendFunSwallowedCancellation")
    private suspend fun transferFile(
        token: BookTransferRegistry.Token,
        row: DownloadEntity,
        host: BookTransferHost,
    ): BookTransferOutcome? {
        val shape = BookShape.of(store.rowsFor(row.bookId), row)
        host.onProgress(shape.progress(row.downloadedBytes, resolving = true))
        logger.info {
            "download: start book=${row.bookId} file=${row.audioFileId} index=${shape.fileNumber}/${shape.fileCount} " +
                "transport=${host.transport.name} fg=${host.foreground}"
        }
        val result =
            try {
                downloader.download(
                    audioFileId = row.audioFileId,
                    bookId = row.bookId,
                    filename = row.filename,
                    expectedSize = row.totalBytes,
                    setProgress = { downloaded, _ -> host.onProgress(shape.progress(downloaded, resolving = false)) },
                )
            } catch (e: CancellationException) {
                withContext(NonCancellable) { recordStop(token, row, host) }
                throw e
            }
        return when (result) {
            is AppResult.Success -> null
            is AppResult.Failure -> settle(row, result.error, host)
        }
    }

    private suspend fun settle(
        row: DownloadEntity,
        error: AppError,
        host: BookTransferHost,
    ): BookTransferOutcome {
        val verdict =
            settleDownloadFailure(
                audioFileId = row.audioFileId,
                error = error,
                repository = repository,
                errorBus = errorBus,
            )
        // A failure that turned out to be a wait (a lost connection, an ended session) is a stop too.
        val settled = store.rowsFor(row.bookId).firstOrNull { it.audioFileId == row.audioFileId }
        val waitReason =
            when (settled?.takeIf { it.state == DownloadState.PAUSED }?.pauseReason) {
                DownloadPauseReason.NETWORK_LOST -> DownloadStopReason.NETWORK_LOST

                DownloadPauseReason.SIGN_IN_REQUIRED -> DownloadStopReason.AUTH_EXPIRED

                DownloadPauseReason.INTERRUPTED,
                DownloadPauseReason.PAUSED_BY_YOU,
                DownloadPauseReason.APP_CLOSED,
                DownloadPauseReason.URL_EXPIRED,
                null,
                -> null
            }
        waitReason?.let { record(row, StopCause(it), host) }
        return when (verdict) {
            DownloadFailureVerdict.RETRY_LATER -> BookTransferOutcome.RETRY_LATER
            DownloadFailureVerdict.GIVE_UP -> BookTransferOutcome.GAVE_UP
        }
    }

    /** The run was stopped mid-file: keep the partial, pause the file, and say why. */
    private suspend fun recordStop(
        token: BookTransferRegistry.Token,
        row: DownloadEntity,
        host: BookTransferHost,
    ) {
        val platform = host.platformStopCause()
        val cause = registry.expectedStopOf(token)?.let { StopCause(it, platform.code) } ?: platform
        persistDownloadCancellation(
            repository = repository,
            fileManager = fileManager,
            audioFileId = row.audioFileId,
            bookId = row.bookId,
            filename = row.filename,
        )
        record(row, cause, host)
    }

    private suspend fun record(
        row: DownloadEntity,
        cause: StopCause,
        host: BookTransferHost,
    ) {
        val rows = store.rowsFor(row.bookId).filter { file -> file.state.isLive() }
        val stop =
            DownloadStop(
                at = now(),
                bookId = row.bookId,
                audioFileId = row.audioFileId,
                cause = cause,
                bytesAtStop = rows.sumOf { it.downloadedBytes },
                totalBytes = rows.sumOf { it.totalBytes },
                transport = host.transport,
                foreground = host.foreground,
                device = deviceState.snapshot(),
            )
        logger.info { stop.logLine() }
        // The history is diagnostics: a write that fails must never turn a stop into a crash.
        suspendRunCatching { store.recordStop(stop) }.onFailure {
            logger.warn { "download: could not record stop book=${row.bookId} file=${row.audioFileId}" }
        }
    }
}

/** A book's size and where [current] sits in it, fixed when the file starts. */
private data class BookShape(
    val bookId: String,
    val completedBytes: Long,
    val totalBytes: Long,
    val fileNumber: Int,
    val fileCount: Int,
) {
    fun progress(
        currentBytes: Long,
        resolving: Boolean,
    ) = BookTransferProgress(
        bookId = bookId,
        downloadedBytes = (completedBytes + currentBytes).coerceAtMost(totalBytes),
        totalBytes = totalBytes,
        fileNumber = fileNumber,
        fileCount = fileCount,
        resolving = resolving,
    )

    companion object {
        fun of(
            rows: List<DownloadEntity>,
            current: DownloadEntity,
        ): BookShape {
            val book = rows.filter { file -> file.state.isLive() }
            return BookShape(
                bookId = current.bookId,
                completedBytes = book.filter { it.state == DownloadState.COMPLETED }.sumOf { it.totalBytes },
                totalBytes = book.sumOf { it.totalBytes },
                fileNumber = book.indexOfFirst { it.audioFileId == current.audioFileId } + 1,
                fileCount = book.size,
            )
        }
    }
}

/** A file still part of the book: not cancelled, not a deletion tombstone. */
private fun DownloadState.isLive(): Boolean = this != DownloadState.CANCELLED && this != DownloadState.DELETED
