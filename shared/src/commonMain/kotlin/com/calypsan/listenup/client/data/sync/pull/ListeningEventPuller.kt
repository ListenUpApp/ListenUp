@file:Suppress("SwallowedException")

package com.calypsan.listenup.client.data.sync.pull

import com.calypsan.listenup.client.core.BookId
import com.calypsan.listenup.client.core.currentEpochMilliseconds
import com.calypsan.listenup.client.data.local.db.ListeningEventDao
import com.calypsan.listenup.client.data.local.db.ListeningEventEntity
import com.calypsan.listenup.client.data.local.db.PlaybackPositionDao
import com.calypsan.listenup.client.data.local.db.PlaybackPositionEntity
import com.calypsan.listenup.client.data.local.db.SyncState
import com.calypsan.listenup.client.data.remote.SyncApiContract
import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.data.sync.model.SyncPhase
import com.calypsan.listenup.client.data.sync.model.SyncStatus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Instant
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.client.core.Failure

private val logger = KotlinLogging.logger {}

/**
 * Contract for listening event puller with additional refresh capability.
 */
interface ListeningEventPullerContract : Puller {
    /**
     * Pull ALL listening events from server, ignoring the delta sync cursor.
     * Used after importing historical data.
     */
    suspend fun pullAll()
}

/**
 * Handles syncing listening events from server.
 *
 * Fetches listening events from other devices to populate local stats.
 * Events are synced as SYNCED since they already exist on the server.
 *
 * Also creates/updates playback positions from synced events so that
 * Continue Listening works correctly after syncing from another device.
 */
class ListeningEventPuller(
    private val syncApi: SyncApiContract,
    private val listeningEventDao: ListeningEventDao,
    private val playbackPositionDao: PlaybackPositionDao,
) : ListeningEventPullerContract {
    /**
     * Pull listening events from server.
     *
     * Non-critical — failures are logged and [AppResult.Success] is still returned
     * so the surrounding sync cycle is not aborted.
     *
     * Uses the latest local event timestamp as a cursor for delta sync,
     * so only new events from other devices are fetched.
     *
     * @param updatedAfter ISO timestamp for delta sync (not used - we use our own cursor)
     * @param onProgress Callback for progress updates
     */
    override suspend fun pull(
        updatedAfter: String?,
        onProgress: (SyncStatus) -> Unit,
    ): AppResult<Unit> {
        logger.debug { "Starting listening events sync..." }

        onProgress(
            SyncStatus.Progress(
                phase = SyncPhase.SYNCING_LISTENING_EVENTS,
                phaseItemsSynced = 0,
                phaseTotalItems = -1,
                message = "Syncing listening events...",
            ),
        )

        try {
            // Get the latest local event timestamp to use as a cursor
            // This ensures we only fetch events we don't already have
            val sinceMs = listeningEventDao.getLatestEventTimestamp()

            logger.debug { "Fetching events since: $sinceMs" }

            when (val result = syncApi.getListeningEvents(sinceMs)) {
                is Success -> {
                    val events = result.data.events
                    logger.info { "Fetched ${events.size} listening events from server" }

                    if (events.isEmpty()) {
                        logger.debug { "No new listening events to sync" }
                        return AppResult.Success(Unit)
                    }

                    // Convert to entities and upsert
                    val entities =
                        events.map { event ->
                            ListeningEventEntity(
                                id = event.id,
                                bookId = event.bookId,
                                startPositionMs = event.startPositionMs,
                                endPositionMs = event.endPositionMs,
                                startedAt = parseTimestamp(event.startedAt),
                                endedAt = parseTimestamp(event.endedAt),
                                playbackSpeed = event.playbackSpeed,
                                deviceId = event.deviceId,
                                syncState = SyncState.SYNCED, // Already on server
                                createdAt = parseTimestamp(event.endedAt), // Use endedAt as createdAt
                            )
                        }

                    listeningEventDao.upsertAll(entities)
                    logger.info { "Listening events sync complete: ${entities.size} events synced" }

                    // Create/update playback positions from synced events
                    // This ensures Continue Listening works after syncing from another device
                    updatePlaybackPositionsFromEvents(entities)
                }

                is Failure -> {
                    logger.warn { "Failed to fetch listening events" }
                    // Don't throw - listening events are not critical for sync
                }
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to sync listening events" }
            // Don't throw - listening events are not critical for sync
        }

        return AppResult.Success(Unit)
    }

    /**
     * Create or update playback positions from synced listening events.
     *
     * For each book with events, finds the latest position (max endPositionMs)
     * and latest play time (max endedAt) to create a playback position.
     *
     * Only updates if the synced position is newer than the existing local position.
     * Uses batch operations for efficiency.
     */
    private suspend fun updatePlaybackPositionsFromEvents(events: List<ListeningEventEntity>) {
        if (events.isEmpty()) return

        // Group events by book and find the latest state for each
        val bookPositions =
            events
                .groupBy { it.bookId }
                .mapValues { (_, bookEvents) ->
                    // Find the event with the latest endedAt (most recent play time)
                    val latestEvent = bookEvents.maxByOrNull { it.endedAt }!!
                    BookPositionInfo(
                        positionMs = latestEvent.endPositionMs,
                        lastPlayedAt = latestEvent.endedAt,
                        playbackSpeed = latestEvent.playbackSpeed,
                    )
                }

        // Batch fetch all existing positions
        val bookIds = bookPositions.keys.map { BookId(it) }
        val existingPositions = playbackPositionDao.getByBookIds(bookIds).associateBy { it.bookId.value }

        val now = currentEpochMilliseconds()
        val toSave = mutableListOf<PlaybackPositionEntity>()
        var created = 0
        var updated = 0
        var skipped = 0

        for ((bookId, info) in bookPositions) {
            val existing = existingPositions[bookId]

            if (existing == null) {
                // No local position - create from synced data
                // Use lastPlayedAt as startedAt since this is the first event we know about
                toSave.add(
                    PlaybackPositionEntity(
                        bookId = BookId(bookId),
                        positionMs = info.positionMs,
                        playbackSpeed = info.playbackSpeed,
                        hasCustomSpeed = false,
                        updatedAt = info.lastPlayedAt,
                        syncedAt = now,
                        lastPlayedAt = info.lastPlayedAt,
                        isFinished = false,
                        finishedAt = null,
                        startedAt = info.lastPlayedAt, // First event = when started
                    ),
                )
                created++
            } else if (info.lastPlayedAt > (existing.lastPlayedAt ?: existing.updatedAt)) {
                // Synced position is newer - update local
                // Preserve isFinished, finishedAt, startedAt from existing
                toSave.add(
                    existing.copy(
                        positionMs = info.positionMs,
                        playbackSpeed = info.playbackSpeed,
                        updatedAt = info.lastPlayedAt,
                        syncedAt = now,
                        lastPlayedAt = info.lastPlayedAt,
                    ),
                )
                updated++
            } else {
                // Local position is newer or same - skip
                skipped++
            }
        }

        // Batch save all changes
        if (toSave.isNotEmpty()) {
            playbackPositionDao.saveAll(toSave)
        }

        logger.info {
            "Updated playback positions from events: $created created, $updated updated, $skipped skipped"
        }
    }

    /**
     * Pull ALL listening events from server, ignoring the delta sync cursor.
     *
     * Used after importing historical data (e.g., from Audiobookshelf) to fetch
     * events that wouldn't be included in a normal delta sync due to their
     * old timestamps.
     */
    override suspend fun pullAll() {
        logger.info { "Starting full listening events refresh (ignoring cursor)..." }

        try {
            // Fetch ALL events by passing null as the since parameter
            when (val result = syncApi.getListeningEvents(sinceMs = null)) {
                is Success -> {
                    val events = result.data.events
                    logger.info { "Full refresh: fetched ${events.size} listening events from server" }

                    if (events.isEmpty()) {
                        logger.debug { "No listening events on server" }
                        return
                    }

                    // Convert to entities and upsert
                    val entities =
                        events.map { event ->
                            ListeningEventEntity(
                                id = event.id,
                                bookId = event.bookId,
                                startPositionMs = event.startPositionMs,
                                endPositionMs = event.endPositionMs,
                                startedAt = parseTimestamp(event.startedAt),
                                endedAt = parseTimestamp(event.endedAt),
                                playbackSpeed = event.playbackSpeed,
                                deviceId = event.deviceId,
                                syncState = SyncState.SYNCED,
                                createdAt = parseTimestamp(event.endedAt),
                            )
                        }

                    listeningEventDao.upsertAll(entities)
                    logger.info { "Full refresh: stored ${entities.size} events" }

                    // Rebuild playback positions from ALL events
                    updatePlaybackPositionsFromEvents(entities)
                }

                is Failure -> {
                    logger.warn { "Full refresh: failed to fetch listening events" }
                }
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Full refresh: failed to sync listening events" }
        }
    }

    private data class BookPositionInfo(
        val positionMs: Long,
        val lastPlayedAt: Long,
        val playbackSpeed: Float,
    )

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
