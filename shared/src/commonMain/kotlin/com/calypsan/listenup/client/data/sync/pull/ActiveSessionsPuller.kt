@file:Suppress("SwallowedException")

package com.calypsan.listenup.client.data.sync.pull

import com.calypsan.listenup.client.core.currentEpochMilliseconds
import com.calypsan.listenup.client.data.local.db.ActiveSessionDao
import com.calypsan.listenup.client.data.local.db.ActiveSessionEntity
import com.calypsan.listenup.client.data.local.db.UserProfileDao
import com.calypsan.listenup.client.data.local.db.UserProfileEntity
import com.calypsan.listenup.client.data.remote.SyncApiContract
import com.calypsan.listenup.client.data.sync.ImageDownloaderContract
import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.data.sync.model.SyncPhase
import com.calypsan.listenup.client.data.sync.model.SyncStatus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.time.Instant
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.client.core.Failure

private val logger = KotlinLogging.logger {}

/**
 * Handles syncing active reading sessions from server.
 *
 * Fetches all currently active reading sessions to populate the
 * "What Others Are Listening To" section on the Discover page.
 *
 * Unlike other pullers, this does a full sync each time (no delta)
 * because active sessions are ephemeral and we need the current state.
 * SSE events will keep the data updated after initial sync.
 *
 * Also syncs user profiles for session owners to enable offline-first display,
 * and downloads avatars for users with image avatars.
 */
class ActiveSessionsPuller(
    private val syncApi: SyncApiContract,
    private val activeSessionDao: ActiveSessionDao,
    private val userProfileDao: UserProfileDao,
    private val imageDownloader: ImageDownloaderContract,
) : Puller {
    /**
     * Pull active sessions from server.
     *
     * Non-critical — failures are logged and [AppResult.Success] is still returned
     * so the surrounding sync cycle is not aborted.
     *
     * Replaces all existing active sessions with the current server state.
     * This ensures any stale sessions (from missed SSE events) are cleared.
     *
     * @param updatedAfter Ignored - always fetches all active sessions
     * @param onProgress Callback for progress updates
     */
    override suspend fun pull(
        updatedAfter: String?,
        onProgress: (SyncStatus) -> Unit,
    ): AppResult<Unit> {
        logger.debug { "Starting active sessions sync..." }

        onProgress(
            SyncStatus.Progress(
                phase = SyncPhase.SYNCING_LISTENING_EVENTS,
                phaseItemsSynced = 0,
                phaseTotalItems = -1,
                message = "Syncing active sessions...",
            ),
        )

        try {
            when (val result = syncApi.getActiveSessions()) {
                is Success -> {
                    val sessions = result.data.sessions
                    logger.info { "Fetched ${sessions.size} active sessions from server" }

                    // Clear existing sessions and replace with fresh data
                    // This handles sessions that may have ended while offline
                    activeSessionDao.deleteAll()

                    if (sessions.isEmpty()) {
                        logger.debug { "No active sessions to sync" }
                        return AppResult.Success(Unit)
                    }

                    val now = currentEpochMilliseconds()

                    // Save user profiles for offline display
                    // Do this BEFORE saving sessions so the join query works
                    val userProfiles =
                        sessions.map { session ->
                            logger.info {
                                "Session user: ${session.userId}, " +
                                    "displayName=${session.displayName}, " +
                                    "avatarType=${session.avatarType}, " +
                                    "avatarValue=${session.avatarValue}"
                            }
                            UserProfileEntity(
                                id = session.userId,
                                displayName = session.displayName,
                                avatarType = session.avatarType,
                                avatarValue = session.avatarValue,
                                avatarColor = session.avatarColor,
                                updatedAt = now,
                            )
                        }
                    userProfileDao.upsertAll(userProfiles)
                    logger.info { "Saved ${userProfiles.size} user profiles for active sessions" }

                    // Download avatars for users with image avatars (in parallel)
                    val usersWithImageAvatars = sessions.filter { it.avatarType == "image" }
                    logger.info { "Users with image avatars: ${usersWithImageAvatars.size}" }
                    if (usersWithImageAvatars.isNotEmpty()) {
                        coroutineScope {
                            usersWithImageAvatars
                                .map { session ->
                                    async {
                                        try {
                                            logger.debug { "Downloading avatar for user ${session.userId}..." }
                                            imageDownloader.downloadUserAvatar(
                                                session.userId,
                                                forceRefresh = false,
                                            )
                                        } catch (
                                            e: kotlin.coroutines.cancellation.CancellationException,
                                        ) {
                                            throw e
                                        } catch (e: Exception) {
                                            logger.warn(e) { "Failed to download avatar for user ${session.userId}" }
                                            null
                                        }
                                    }
                                }.awaitAll()
                        }
                        logger.info { "Completed downloading ${usersWithImageAvatars.size} avatars" }
                    }

                    // Convert to session entities and insert
                    val entities =
                        sessions.map { session ->
                            ActiveSessionEntity(
                                sessionId = session.sessionId,
                                userId = session.userId,
                                bookId = session.bookId,
                                startedAt = parseTimestamp(session.startedAt),
                                updatedAt = now,
                            )
                        }

                    activeSessionDao.upsertAll(entities)
                    logger.info { "Active sessions sync complete: ${entities.size} sessions synced" }
                }

                is Failure -> {
                    logger.warn { "Failed to fetch active sessions" }
                    // Don't throw - active sessions are not critical for sync
                }
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to sync active sessions" }
            // Don't throw - active sessions are not critical for sync
        }

        return AppResult.Success(Unit)
    }

    /**
     * Parse ISO timestamp string to epoch milliseconds.
     */
    private fun parseTimestamp(isoTimestamp: String): Long =
        try {
            Instant.parse(isoTimestamp).toEpochMilliseconds()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn { "Failed to parse timestamp: $isoTimestamp, using current time" }
            currentEpochMilliseconds()
        }
}
