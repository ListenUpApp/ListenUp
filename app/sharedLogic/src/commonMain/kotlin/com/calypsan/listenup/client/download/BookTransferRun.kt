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
import kotlinx.coroutines.delay
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
 * on ([fileNumber] of [fileCount]), and whether it is still [resolving]: no byte of the book has arrived yet, so the
 * progress is indeterminate. A later file's start keeps the bar where the book is.
 */
internal data class BookTransferProgress(
    val bookId: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val fileNumber: Int,
    val fileCount: Int,
    val resolving: Boolean,
)

/**
 * How long a run that stopped for the platform, or lost its network, keeps its turn before the next book may start.
 * The platform stops every job a lost network or a quota rules out together, a moment after the running one notices;
 * a queued run stopped inside this window ends without having started, so it is not charged with the stop.
 */
internal const val TURN_HANDOFF_GRACE_MS = 5_000L

/**
 * How a run that had its turn ended: its [outcome] (null when it was stopped) and, when the app stopped it, the reason
 * the app gave ([appStop]).
 */
internal data class BookTransferEnd(
    val outcome: BookTransferOutcome?,
    val appStop: DownloadStopReason?,
) {
    /**
     * Whether the run should say its download is waiting, if it is. Not after it completed, and not when the person
     * paused or cancelled it: those actions say what they did themselves, and a cancelled download is not waiting.
     */
    val announcesWait: Boolean
        get() =
            outcome != BookTransferOutcome.COMPLETED &&
                appStop != DownloadStopReason.USER_CANCELLED &&
                appStop != DownloadStopReason.USER_PAUSED
}

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

    /**
     * A run that had its turn ended, as [end] says, before it hands the turn on. Called under NonCancellable. A run
     * stopped while it waited for its turn never had one and is not reported.
     */
    suspend fun onEnded(end: BookTransferEnd) {}

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
 * - A file left DOWNLOADING that no run in this process is transferring was being transferred by a process that died.
 *   Every run takes such files back before it waits for its turn ([releaseOrphans]), so a book the platform restarts
 *   after a process death resumes them from their partials.
 * - A run that lost its network or was stopped by the platform keeps its turn for [TURN_HANDOFF_GRACE_MS], so the stop
 *   the platform makes of the books behind it lands before any of them starts.
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
            releaseOrphans(host.transport)
            return registry.withTurn { takeTurn(token, host) }
        } finally {
            withContext(NonCancellable) { registry.unregister(token) }
        }
    }

    /**
     * Take back every file left DOWNLOADING that no run in this process is transferring: the process transferring it
     * died. Each is paused to resume from its partial and its stop is recorded as [DownloadStopReason.PROCESS_DEATH],
     * with the platform's record of the death that ended it. Returns the files it took back.
     */
    suspend fun releaseOrphans(transport: DownloadTransport): List<DownloadEntity> {
        val orphans = registry.withOwnedFiles { owned -> store.releaseOrphans(excluding = owned) }
        orphans.forEach { row ->
            val death = StopCause(DownloadStopReason.PROCESS_DEATH, deviceState.processDeathCode(row.startedAt))
            record(row = row, cause = death, transport = transport, foreground = null)
        }
        return orphans
    }

    /** The run's turn: transfer the book, then tell the host how it ended and, after a stop, hold the turn a moment. */
    private suspend fun takeTurn(
        token: BookTransferRegistry.Token,
        host: BookTransferHost,
    ): BookTransferOutcome {
        // Null until the book's transfers return: a stop (or a fault) ends the turn without an outcome.
        var outcome: BookTransferOutcome? = null
        try {
            host.onTurn()
            return transferAll(token, host).also { outcome = it }
        } finally {
            withContext(NonCancellable) { endTurn(token, host, outcome) }
        }
    }

    private suspend fun endTurn(
        token: BookTransferRegistry.Token,
        host: BookTransferHost,
        outcome: BookTransferOutcome?,
    ) {
        val appStop = registry.expectedStopOf(token)
        host.onEnded(BookTransferEnd(outcome, appStop))
        val platformStopOrWait = outcome == BookTransferOutcome.RETRY_LATER || (outcome == null && appStop == null)
        if (platformStopOrWait) delay(TURN_HANDOFF_GRACE_MS)
    }

    private suspend fun transferAll(
        token: BookTransferRegistry.Token,
        host: BookTransferHost,
    ): BookTransferOutcome {
        while (true) {
            val row = registry.nextOrFinish(token) { store.nextForRun(token.bookId) } ?: break
            if (!registry.claim(row.audioFileId) { store.claim(row.audioFileId, now()) }) continue
            val outcome =
                try {
                    transferFile(token, row, host)
                } finally {
                    withContext(NonCancellable) { registry.release(row.audioFileId) }
                }
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
        waitReason?.let { reason ->
            record(
                row = row,
                cause = StopCause(reason),
                transport = host.transport,
                foreground = host.foreground,
            )
        }
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
        record(row = row, cause = cause, transport = host.transport, foreground = host.foreground)
    }

    private suspend fun record(
        row: DownloadEntity,
        cause: StopCause,
        transport: DownloadTransport,
        foreground: Boolean?,
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
                transport = transport,
                foreground = foreground,
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
    /** Where the book is with [currentBytes] of this file. Indeterminate only while the book has no bytes at all. */
    fun progress(
        currentBytes: Long,
        resolving: Boolean,
    ): BookTransferProgress {
        val downloaded = (completedBytes + currentBytes).coerceAtMost(totalBytes)
        return BookTransferProgress(
            bookId = bookId,
            downloadedBytes = downloaded,
            totalBytes = totalBytes,
            fileNumber = fileNumber,
            fileCount = fileCount,
            resolving = resolving && downloaded == 0L,
        )
    }

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
