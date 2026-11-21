package com.calypsan.listenup.client.download

import android.app.Notification
import com.calypsan.listenup.api.result.onFailure
import com.calypsan.listenup.client.core.suspendRunCatching
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.DownloadState
import com.calypsan.listenup.client.data.repository.BookTransferStore
import com.calypsan.listenup.client.data.repository.aggregateBookDownloadStatus
import com.calypsan.listenup.client.domain.model.DownloadPhase
import com.calypsan.listenup.client.domain.model.downloadPhaseOf
import com.calypsan.listenup.client.domain.repository.LocalPreferences
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.currentEpochMilliseconds
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

private val logger = KotlinLogging.logger {}

/** At most one notification update a second: the shade does not need more, and each one is a binder call. */
private const val NOTIFICATION_INTERVAL_MS = 1_000L

/**
 * What runs a book on Android: the user-initiated job or the WorkManager worker, as [BookTransfers] sees it.
 */
internal interface PlatformTransferHost {
    /** The transport, recorded with every stop. */
    val transport: DownloadTransport

    /** Whether the run holds a foreground (or user-visible) state. */
    val foreground: Boolean

    /** Show [notification] as the running download's. */
    suspend fun post(notification: Notification)

    /** [bytes] have arrived since the run started; a job reports it so the system sees it making progress. */
    fun reportTransferred(bytes: Long) {}

    /** Why the platform stopped the run. */
    fun platformStopCause(): StopCause

    /** The run ended; a host that posted its notification itself removes it. */
    fun finished() {}
}

/** What a platform host needs to run a book: the run itself and the notification it shows. */
internal interface BookRunner {
    /** The id every running download's notification shares. */
    val activeNotificationId: Int

    /** The notification a newly started run shows before its turn. */
    fun startingNotification(
        bookId: String,
        title: String,
    ): Notification

    /** Post a running download's notification directly (a worker the platform would not promote). */
    fun postActive(notification: Notification)

    /** Remove a notification posted with [postActive]. */
    fun cancelActive()

    /** Run [bookId] on [host]. */
    suspend fun runOn(
        bookId: String,
        host: PlatformTransferHost,
    ): BookTransferOutcome
}

/** [this] as the [BookRunner] a platform host runs books through. */
internal fun BookTransfers.asRunner(): BookRunner {
    val transfers = this
    return object : BookRunner {
        override val activeNotificationId: Int get() = transfers.activeNotificationId

        override fun startingNotification(
            bookId: String,
            title: String,
        ): Notification = transfers.startingNotification(bookId, title)

        override fun postActive(notification: Notification) = transfers.postActive(notification)

        override fun cancelActive() = transfers.cancelActive()

        override suspend fun runOn(
            bookId: String,
            host: PlatformTransferHost,
        ): BookTransferOutcome = transfers.runOn(bookId, host)
    }
}

/**
 * Android's book downloads: scheduling, running, pausing and cancelling a book on whichever transport the device has
 * ([BookTransferScheduler]), around the shared [BookTransferRun].
 *
 * Public only so the app module can hand it to the job service and worker it constructs; everything it does is
 * internal to this module.
 */
@Suppress("LongParameterList")
class BookTransfers internal constructor(
    private val run: BookTransferRun,
    private val store: BookTransferStore,
    private val registry: BookTransferRegistry,
    private val scheduler: BookTransferScheduler,
    private val notifications: DownloadNotifications,
    private val bookDao: BookDao,
    private val fileManager: DownloadFileManager,
    private val localPreferences: LocalPreferences,
    private val networkMonitor: NetworkMonitor,
    private val deviceState: AndroidDownloadDeviceState,
    /** The application scope runs are launched on by the job service. */
    internal val scope: CoroutineScope,
    private val now: () -> Long = ::currentEpochMilliseconds,
) {
    /** The latest active notification, so a run still waiting for its turn shows the running one rather than its own. */
    @Volatile
    private var latestActive: Notification? = null

    /** The transport this device uses. */
    internal val transport: DownloadTransport get() = scheduler.transport

    /** The notification a newly started run shows before its turn. */
    internal fun startingNotification(
        bookId: String,
        title: String,
    ): Notification =
        latestActive ?: notifications.active(
            DownloadNotice.Active(
                bookId = bookId,
                title = title,
                downloadedBytes = 0,
                totalBytes = 0,
                fileNumber = 1,
                fileCount = 1,
                queuedBehind = 0,
                resolving = true,
            ),
        )

    /** The id every running download's notification shares. */
    internal val activeNotificationId: Int get() = notifications.activeNotificationId

    /** Post a running download's notification directly (a worker the platform would not promote). */
    internal fun postActive(notification: Notification) = notifications.postActive(notification)

    /** Remove a notification posted with [postActive]. */
    internal fun cancelActive() = notifications.cancelActive()

    /**
     * Schedule [bookId]'s run, unless one is already registered here (it picks up whatever was queued). With
     * [replace], replace whatever is scheduled or running, recording the stop as [replaceReason].
     */
    internal suspend fun schedule(
        bookId: String,
        replace: Boolean = false,
        replaceReason: DownloadStopReason = DownloadStopReason.PREFERENCE_CHANGED,
    ) {
        val request = requestFor(bookId)
        val result =
            if (replace) {
                registry.expectStop(bookId, replaceReason)
                scheduler.schedule(request, replace = true)
            } else {
                registry.scheduleUnlessRunning(bookId) { scheduler.schedule(request, replace = false) }
            }
        logger.info {
            "download: schedule book=$bookId transport=${scheduler.transport.name} result=${result ?: "RUNNING"}"
        }
        if (result == ScheduleResult.REFUSED) recordBookStop(bookId, StopCause(DownloadStopReason.SCHEDULE_REFUSED))
    }

    /**
     * Account for every unfinished download at a start of the app (or its return to the foreground): a file a dead
     * process left mid-transfer is recorded as a process death and queued again, and every book with something to
     * transfer gets a run unless it already has one, here or with the platform.
     */
    internal suspend fun resumeAll() {
        val stale =
            store.incompleteRows().filter { row ->
                row.state == DownloadState.DOWNLOADING &&
                    !registry.isRunning(row.bookId)
            }
        if (stale.isNotEmpty()) {
            val exit = deviceState.lastExitReason()
            stale.forEach { row ->
                store.requeueStale(row.audioFileId)
                recordStop(row.bookId, row.audioFileId, StopCause(DownloadStopReason.PROCESS_DEATH, exit))
            }
        }
        for (bookId in store.runnableBooks()) {
            if (registry.isRunning(bookId) || scheduler.isScheduled(bookId)) continue
            schedule(bookId)
        }
    }

    /** Re-schedule every book with something to transfer under the current Wi-Fi-only rule. */
    internal suspend fun reapplyNetworkRule() {
        store.runnableBooks().forEach { bookId -> schedule(bookId, replace = true) }
    }

    /**
     * Pause [bookId] for the person: every unfinished file keeps its partial and waits for them. From the notification
     * the notification says so, with Resume; from inside the app it is withdrawn, since the app already shows it.
     */
    internal suspend fun pause(
        bookId: String,
        fromNotification: Boolean,
    ) {
        registry.expectStop(bookId, DownloadStopReason.USER_PAUSED)
        val paused = store.pauseForUser(bookId)
        scheduler.cancel(bookId)
        logger.info {
            "download: pause book=$bookId files=$paused from=${if (fromNotification) "notification" else "app"}"
        }
        if (fromNotification) showHeld(bookId) else notifications.clearHeld()
    }

    /** Stop [bookId]'s run, for a caller that deals with the rows and files itself (removing a download). */
    internal suspend fun cancelRun(bookId: String) {
        registry.expectStop(bookId, DownloadStopReason.USER_CANCELLED)
        scheduler.cancel(bookId)
        notifications.clearHeld()
    }

    /** Cancel [bookId]: stop its run and discard everything it saved. */
    internal suspend fun cancel(bookId: String) {
        registry.expectStop(bookId, DownloadStopReason.USER_CANCELLED)
        store.discard(bookId)
        scheduler.cancel(bookId)
        if (!fileManager.deleteBookFiles(bookId)) logger.warn { "download: cancel left files behind book=$bookId" }
        notifications.clearHeld()
        logger.info { "download: cancel book=$bookId" }
    }

    /**
     * Run [bookId] on [host], showing its notification and keeping the platform informed of its progress. A stop is
     * recorded by the run and re-thrown; afterwards a download that waits to resume says so on the held notification.
     */
    internal suspend fun runOn(
        bookId: String,
        host: PlatformTransferHost,
    ): BookTransferOutcome {
        val title = titleOf(bookId)
        val adapter = NotifyingHost(bookId, title, host)
        var outcome: BookTransferOutcome? = null
        try {
            outcome = run.run(bookId, adapter)
            return outcome
        } finally {
            latestActive = null
            host.finished()
            // A completed book clears the held notification; anything else (a wait, a stop) says why it is waiting.
            withContext(NonCancellable) {
                if (outcome == BookTransferOutcome.COMPLETED) notifications.clearHeld() else showHeldIfWaiting(bookId)
            }
        }
    }

    private inner class NotifyingHost(
        private val bookId: String,
        private val title: String,
        private val host: PlatformTransferHost,
    ) : BookTransferHost {
        override val transport: DownloadTransport get() = host.transport
        override val foreground: Boolean get() = host.foreground

        private var lastPostedAt = 0L
        private var lastFile = -1
        private var startBytes = -1L

        override suspend fun onTurn() {
            notifications.clearHeld()
        }

        override suspend fun onProgress(progress: BookTransferProgress) {
            if (startBytes < 0) startBytes = progress.downloadedBytes
            host.reportTransferred(progress.downloadedBytes - startBytes)
            val at = now()
            val due =
                progress.resolving || progress.fileNumber != lastFile || at - lastPostedAt >= NOTIFICATION_INTERVAL_MS
            if (!due) return
            lastPostedAt = at
            lastFile = progress.fileNumber
            val notification =
                notifications.active(
                    DownloadNotice.Active(
                        bookId = bookId,
                        title = title,
                        downloadedBytes = progress.downloadedBytes,
                        totalBytes = progress.totalBytes,
                        fileNumber = progress.fileNumber,
                        fileCount = progress.fileCount,
                        queuedBehind = store.otherActiveBooks(bookId),
                        resolving = progress.resolving,
                    ),
                )
            latestActive = notification
            host.post(notification)
        }

        override fun platformStopCause(): StopCause = host.platformStopCause()
    }

    /** After a run stops, say so if the download waits to resume (for Wi-Fi, or for the system). */
    private suspend fun showHeldIfWaiting(bookId: String) {
        when (phaseOf(bookId)) {
            DownloadPhase.WaitingForWifi, DownloadPhase.WaitingToResume -> {
                showHeld(bookId)
            }

            DownloadPhase.NotDownloaded, DownloadPhase.Queued, DownloadPhase.Downloaded,
            is DownloadPhase.Downloading, is DownloadPhase.PausedByYou, is DownloadPhase.Failed,
            -> {
                // Running, done, or the person's to decide: nothing waits, so nothing to say.
            }
        }
    }

    private suspend fun showHeld(bookId: String) {
        val rows = store.rowsFor(bookId)
        val kind =
            when (phaseOf(bookId)) {
                DownloadPhase.WaitingForWifi -> DownloadNotice.HeldKind.WAITING_FOR_WIFI

                DownloadPhase.WaitingToResume -> DownloadNotice.HeldKind.WAITING_TO_RESUME

                is DownloadPhase.PausedByYou -> DownloadNotice.HeldKind.PAUSED_BY_YOU

                DownloadPhase.NotDownloaded, DownloadPhase.Queued, DownloadPhase.Downloaded,
                is DownloadPhase.Downloading, is DownloadPhase.Failed,
                -> return
            }
        val live = rows.filter { row -> row.state.isLive() }
        notifications.showHeld(
            DownloadNotice.Held(
                bookId = bookId,
                title = titleOf(bookId),
                kind = kind,
                downloadedBytes = live.sumOf { it.downloadedBytes },
                totalBytes = live.sumOf { it.totalBytes },
            ),
        )
    }

    private suspend fun phaseOf(bookId: String): DownloadPhase =
        downloadPhaseOf(
            aggregateBookDownloadStatus(bookId, store.rowsFor(bookId)),
            wifiBlocked = localPreferences.wifiOnlyDownloads.value && !networkMonitor.onUnmeteredNetworkFlow.value,
        )

    private suspend fun requestFor(bookId: String): BookTransferRequest {
        val rows =
            store.rowsFor(bookId).filter { row -> row.state.isLive() }
        return BookTransferRequest(
            bookId = bookId,
            title = titleOf(bookId),
            wifiOnly = localPreferences.wifiOnlyDownloads.value,
            remainingBytes =
                rows
                    .filter { it.state != DownloadState.COMPLETED }
                    .sumOf { (it.totalBytes - it.downloadedBytes).coerceAtLeast(0L) },
        )
    }

    private suspend fun titleOf(bookId: String): String = bookDao.getById(BookId(bookId))?.title.orEmpty()

    /** A stop that belongs to the whole book (a refused schedule): recorded against no file. */
    private suspend fun recordBookStop(
        bookId: String,
        cause: StopCause,
    ) = recordStop(bookId, audioFileId = null, cause = cause)

    private suspend fun recordStop(
        bookId: String,
        audioFileId: String?,
        cause: StopCause,
    ) {
        val rows =
            store.rowsFor(bookId).filter { row -> row.state.isLive() }
        val stop =
            DownloadStop(
                at = now(),
                bookId = bookId,
                audioFileId = audioFileId,
                cause = cause,
                bytesAtStop = rows.sumOf { it.downloadedBytes },
                totalBytes = rows.sumOf { it.totalBytes },
                transport = scheduler.transport,
                foreground = null,
                device = deviceState.snapshot(),
            )
        logger.info { stop.logLine() }
        // The history is diagnostics: a write that fails must never stop a download being scheduled.
        suspendRunCatching { store.recordStop(stop) }.onFailure {
            logger.warn { "download: could not record stop book=$bookId reason=${cause.reason.name}" }
        }
    }
}

/** A file still part of the book: not cancelled, not a deletion tombstone. */
private fun DownloadState.isLive(): Boolean = this != DownloadState.CANCELLED && this != DownloadState.DELETED
