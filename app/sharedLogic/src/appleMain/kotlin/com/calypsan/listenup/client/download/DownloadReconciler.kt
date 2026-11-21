package com.calypsan.listenup.client.download

import com.calypsan.listenup.client.data.local.db.DownloadEntity
import com.calypsan.listenup.client.data.local.db.DownloadState
import com.calypsan.listenup.client.data.local.db.MAX_DOWNLOAD_RETRIES
import com.calypsan.listenup.client.domain.model.DownloadPauseReason

/** A resume whose URL dies within this many seconds is not worth starting. */
private const val EXPIRY_MARGIN_SECONDS = 60L

/** One task in one of the two background sessions. Task identifiers are unique within a session only. */
internal data class TaskKey(
    val network: DownloadNetwork,
    val taskIdentifier: ULong,
)

/** A transfer the system holds, and the file its tag names (null when the tag is not ours). */
internal data class LiveDownloadTask(
    val key: TaskKey,
    val tag: DownloadTaskTag?,
)

/** What [reconcileDownloads] decided. */
internal data class ReconcilePlan(
    /** Transfers to leave running. */
    val adopt: List<LiveDownloadTask> = emptyList(),
    /** Transfers for files nobody wants: stop them. */
    val cancel: List<LiveDownloadTask> = emptyList(),
    /** Transfers in the session the preference no longer selects: move them, keeping their bytes. */
    val migrate: List<LiveDownloadTask> = emptyList(),
    /** Files to resume from their resume data. */
    val resumeFromBlob: List<DownloadEntity> = emptyList(),
    /** Files to start again from fresh URLs, by book: one `prepare()` per book, not per file. */
    val prepareByBook: Map<String, List<DownloadEntity>> = emptyMap(),
    /** Resume data to throw away. */
    val discardBlobs: Set<String> = emptySet(),
)

/**
 * Reconciles what the database wants with what the system holds.
 *
 * The background sessions' task list survives the process and the in-memory state of the app does
 * not, so at every launch and foreground the two are compared, per file:
 * - a running transfer for a wanted file is left alone, or moved when it is in the session the
 *   Wi-Fi-only preference no longer selects;
 * - a running transfer for a file that is cancelled, deleted, finished, paused by the person or
 *   unknown is stopped, as is a second transfer of the same file;
 * - a wanted file with no transfer resumes from its resume data, unless that data's URL has expired;
 * - otherwise the server is asked for the book's URLs again and the file starts over.
 *
 * [blobs] maps each file with resume data to the expiry (epoch seconds) of the URL it resumes.
 */
internal fun reconcileDownloads(
    rows: List<DownloadEntity>,
    tasks: List<LiveDownloadTask>,
    blobs: Map<String, Long?>,
    network: DownloadNetwork,
    nowEpochSeconds: Long,
): ReconcilePlan {
    val wanted = rows.filter { it.needsTransfer() }.associateBy { it.audioFileId }
    val adopt = mutableListOf<LiveDownloadTask>()
    val cancel = mutableListOf<LiveDownloadTask>()
    val migrate = mutableListOf<LiveDownloadTask>()
    val covered = mutableSetOf<String>()
    for (task in tasks) {
        val fileId = task.tag?.audioFileId
        when {
            fileId == null || fileId !in wanted || !covered.add(fileId) -> cancel += task
            task.key.network != network -> migrate += task
            else -> adopt += task
        }
    }

    val untransferred = wanted.values.filter { it.audioFileId !in covered }
    val (resumable, restart) =
        untransferred.partition { row ->
            row.audioFileId in blobs &&
                blobs[row.audioFileId].let { it == null || it > nowEpochSeconds + EXPIRY_MARGIN_SECONDS }
        }
    val resumableIds = resumable.mapTo(mutableSetOf()) { it.audioFileId }
    // Only resume data about to be used survives: an expired URL's, a file's that is still
    // transferring, and any for a file nobody wants, go.
    val discard = blobs.keys - resumableIds

    return ReconcilePlan(
        adopt = adopt,
        cancel = cancel,
        migrate = migrate,
        resumeFromBlob = resumable,
        prepareByBook = restart.groupBy { it.bookId },
        discardBlobs = discard,
    )
}

/**
 * Whether a row is waiting for bytes: the same set the every-launch resume has always used, without
 * the person's own pause.
 */
private fun DownloadEntity.needsTransfer(): Boolean =
    when (state) {
        DownloadState.QUEUED, DownloadState.DOWNLOADING -> true
        DownloadState.PAUSED -> pauseReason != DownloadPauseReason.PAUSED_BY_YOU
        DownloadState.FAILED -> retryCount < MAX_DOWNLOAD_RETRIES
        DownloadState.COMPLETED, DownloadState.DELETED, DownloadState.CANCELLED -> false
    }
