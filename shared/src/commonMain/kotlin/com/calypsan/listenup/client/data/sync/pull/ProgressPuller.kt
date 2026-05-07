package com.calypsan.listenup.client.data.sync.pull

import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.core.BookId
import com.calypsan.listenup.client.data.local.db.PendingOperationDao
import com.calypsan.listenup.client.data.local.db.PlaybackPositionDao
import com.calypsan.listenup.client.data.local.db.PlaybackPositionEntity
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.remote.SyncApiContract
import com.calypsan.listenup.client.data.sync.model.SyncPhase
import com.calypsan.listenup.client.data.sync.model.SyncStatus
import io.github.oshai.kotlinlogging.KotlinLogging
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.client.core.Failure

private val logger = KotlinLogging.logger {}

/**
 * Handles syncing playback progress from server.
 *
 * Fetches all progress records including isFinished status to ensure
 * the client has accurate finished state for Continue Listening filtering.
 *
 * This is essential for:
 * - Cross-device sync: User finishes book on phone, tablet needs to know
 * - Fresh install: User reinstalls app, all finished state should sync
 * - ABS import: Imported progress includes isFinished from Audiobookshelf
 */
class ProgressPuller(
    private val syncApi: SyncApiContract,
    private val playbackPositionDao: PlaybackPositionDao,
    private val pendingOperationDao: PendingOperationDao,
    private val transactionRunner: TransactionRunner,
) : Puller {
    /**
     * Pull progress from server and upsert locally.
     *
     * Merges server progress with local records, preserving local-only fields
     * (playbackSpeed, hasCustomSpeed) while syncing server fields (position,
     * isFinished, lastPlayedAt).
     *
     * @param updatedAfter ISO-8601 timestamp; when non-null, only rows updated server-side
     *                     after this point are returned (SP2 delta sync). When null, all
     *                     progress is fetched (used for full sync and `refreshListeningHistory`).
     * @param onProgress Callback for progress updates.
     */
    /**
     * Pull progress from server and upsert locally.
     *
     * Non-critical — failures are logged and [AppResult.Success] is still returned
     * so the surrounding sync cycle is not aborted.
     *
     * Merges server progress with local records, preserving local-only fields
     * (playbackSpeed, hasCustomSpeed) while syncing server fields (position,
     * isFinished, lastPlayedAt).
     *
     * @param updatedAfter ISO-8601 timestamp; when non-null, only rows updated server-side
     *                     after this point are returned (SP2 delta sync). When null, all
     *                     progress is fetched (used for full sync and `refreshListeningHistory`).
     * @param onProgress Callback for progress updates.
     */
    override suspend fun pull(
        updatedAfter: String?,
        onProgress: (SyncStatus) -> Unit,
    ): AppResult<Unit> {
        logger.debug { "Starting progress sync (updatedAfter=$updatedAfter)..." }

        onProgress(
            SyncStatus.Progress(
                phase = SyncPhase.SYNCING_LISTENING_EVENTS,
                phaseItemsSynced = 0,
                phaseTotalItems = -1,
                message = "Syncing playback progress...",
            ),
        )

        try {
            when (val result = syncApi.getAllProgress(updatedAfter)) {
                is Success -> {
                    val items = result.data.items
                    logger.info { "Fetched ${items.size} progress records from server" }

                    if (items.isEmpty()) {
                        logger.debug { "No progress records to sync" }
                        return AppResult.Success(Unit)
                    }

                    // Batch fetch existing local positions to preserve local-only fields
                    val bookIds = items.map { BookId(it.bookId) }
                    val existingPositions =
                        playbackPositionDao
                            .getByBookIds(bookIds)
                            .associateBy { it.bookId.value }

                    var created = 0
                    var updated = 0
                    var finishedCount = 0

                    // Convert to entities, preserving local-only fields
                    val entities =
                        items.map { item ->
                            val existing = existingPositions[item.bookId]
                            if (existing == null) created++ else updated++
                            if (item.isFinished) finishedCount++
                            item.toEntity(existing)
                        }

                    guardAndSaveEntities(entities)

                    logger.info {
                        "Progress sync complete: $created created, $updated updated, " +
                            "$finishedCount marked finished"
                    }
                }

                is Failure -> {
                    logger.warn { "Failed to fetch progress" }
                    // Don't throw - progress sync is not critical for basic functionality
                }
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to sync progress" }
            // Don't throw - progress sync is not critical for basic functionality
        }

        return AppResult.Success(Unit)
    }

    /**
     * Apply pending MARK_COMPLETE guards and persist entities in one transaction.
     *
     * Reads pending MARK_COMPLETE ids and writes the guarded entity rows inside a single
     * write transaction so a concurrent MARK_COMPLETE queue cannot commit between the
     * read and the write — closing the single-pull window where a server "not finished"
     * reply could overwrite the user's mark-complete intent.
     */
    private suspend fun guardAndSaveEntities(entities: List<PlaybackPositionEntity>) {
        transactionRunner.atomically {
            val pendingCompleteBookIds =
                pendingOperationDao.getPendingMarkCompleteBookIds().toSet()
            val guardedEntities =
                if (pendingCompleteBookIds.isEmpty()) {
                    entities
                } else {
                    entities.map { entity ->
                        if (entity.bookId.value in pendingCompleteBookIds && !entity.isFinished) {
                            logger.info {
                                "Preserving isFinished=true for ${entity.bookId.value} (pending MARK_COMPLETE)"
                            }
                            entity.copy(isFinished = true)
                        } else {
                            entity
                        }
                    }
                }
            playbackPositionDao.saveAll(guardedEntities)
        }
    }
}
