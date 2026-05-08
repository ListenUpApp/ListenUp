@file:Suppress("SwallowedException")

package com.calypsan.listenup.client.data.sync.pull

import com.calypsan.listenup.client.core.currentEpochMilliseconds
import com.calypsan.listenup.client.data.local.db.ReaderSessionCacheDao
import com.calypsan.listenup.client.data.local.db.ReaderSessionCacheEntity
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.local.db.UserReadingSessionDao
import com.calypsan.listenup.client.data.remote.SyncApiContract
import com.calypsan.listenup.client.data.remote.SyncReadingSessionReaderResponse
import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.data.sync.model.SyncPhase
import com.calypsan.listenup.client.data.sync.model.SyncStatus
import com.calypsan.listenup.client.domain.repository.AuthSession
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Instant
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.client.core.Failure

private val logger = KotlinLogging.logger {}

/**
 * Handles syncing reading-session summaries from the server.
 *
 * The `/api/v1/sync/reading-sessions` endpoint returns one denormalized row per
 * `(bookId, userId)` pair — shape [SyncReadingSessionReaderResponse]. That shape
 * maps naturally onto [ReaderSessionCacheEntity] but cannot faithfully populate
 * [com.calypsan.listenup.client.data.local.db.UserReadingSessionEntity] (which
 * wants per-session rows with `isCompleted` + `listenTimeMs`). So the puller:
 *   - Writes all rows whose `userId != currentUserId` into `reader_sessions_cache`.
 *   - Discards rows for the current user — they'll be populated per-book by
 *     `SessionRepositoryImpl.refreshBookReaders` when the user visits a book's
 *     detail screen (which hits `/api/v1/books/{id}/readers` and returns the
 *     real `yourSessions: List<SessionSummary>`).
 *
 * Writes to both tables in a single atomically block so a failure mid-refresh
 * doesn't leave one table stale while the other is cleared.
 *
 * Like ActiveSessionsPuller, this does a full sync each time (no delta) because
 * we need the complete current state. SSE events keep the data updated after
 * initial sync.
 */
class ReadingSessionPuller(
    private val syncApi: SyncApiContract,
    private val userReadingSessionDao: UserReadingSessionDao,
    private val readerSessionCacheDao: ReaderSessionCacheDao,
    private val transactionRunner: TransactionRunner,
    private val authSession: AuthSession,
) : Puller {
    /**
     * Pull reading sessions from server.
     *
     * Replaces all existing reader-cache rows with the current server state.
     * `user_reading_sessions` is cleared as well — per-book refresh repopulates it.
     *
     * @param updatedAfter Ignored — always fetches all reading sessions.
     * @param onProgress Callback for progress updates.
     */
    override suspend fun pull(
        updatedAfter: String?,
        onProgress: (SyncStatus) -> Unit,
    ): AppResult<Unit> {
        logger.debug { "Starting reading sessions sync..." }

        onProgress(
            SyncStatus.Progress(
                phase = SyncPhase.SYNCING_LISTENING_EVENTS,
                phaseItemsSynced = 0,
                phaseTotalItems = -1,
                message = "Syncing reading sessions...",
            ),
        )

        try {
            when (val result = syncApi.getReadingSessions()) {
                is Success -> {
                    val readers = result.data.readers
                    val currentUserId = authSession.getUserId()
                    val now = currentEpochMilliseconds()

                    // Filter out the current user's rows — their per-session data only
                    // comes from /api/v1/books/{id}/readers via refreshBookReaders.
                    val cacheEntries =
                        readers
                            .filter { it.userId != currentUserId }
                            .map { it.toCacheEntity(now) }

                    logger.info {
                        "Fetched ${readers.size} reader rows from server " +
                            "(${cacheEntries.size} cached, ${readers.size - cacheEntries.size} self-filtered)"
                    }

                    transactionRunner.atomically {
                        // Full-sync semantic: wipe user sessions; per-book refresh via
                        // SessionRepositoryImpl.refreshBookReaders repopulates them on
                        // demand (the sync endpoint returns aggregate shape that can't
                        // faithfully build per-session UserReadingSessionEntity rows).
                        userReadingSessionDao.deleteAll()
                        readerSessionCacheDao.deleteAll()
                        if (cacheEntries.isNotEmpty()) {
                            readerSessionCacheDao.upsertAll(cacheEntries)
                        }
                    }

                    logger.info { "Reading sessions sync complete: ${cacheEntries.size} cache rows synced" }
                }

                is Failure -> {
                    logger.warn { "Failed to fetch reading sessions" }
                    // Don't throw - reading sessions are not critical for sync.
                }
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to sync reading sessions" }
            // Don't throw - reading sessions are not critical for sync.
        }

        return AppResult.Success(Unit)
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// CONVERSION
// ═══════════════════════════════════════════════════════════════════════════

private fun SyncReadingSessionReaderResponse.toCacheEntity(updatedAt: Long): ReaderSessionCacheEntity =
    ReaderSessionCacheEntity(
        bookId = bookId,
        userId = userId,
        userDisplayName = displayName,
        userAvatarColor = avatarColor,
        userAvatarType = avatarType,
        userAvatarValue = avatarValue,
        isCurrentlyReading = isCurrentlyReading,
        currentProgress = currentProgress,
        startedAt = parseTimestampToEpoch(startedAt),
        finishedAt = finishedAt?.let { parseTimestampToEpoch(it) },
        completionCount = completionCount,
        updatedAt = updatedAt,
    )

private fun parseTimestampToEpoch(iso: String): Long =
    try {
        Instant.parse(iso).toEpochMilliseconds()
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.warn { "Failed to parse timestamp: $iso, using current time" }
        currentEpochMilliseconds()
    }
