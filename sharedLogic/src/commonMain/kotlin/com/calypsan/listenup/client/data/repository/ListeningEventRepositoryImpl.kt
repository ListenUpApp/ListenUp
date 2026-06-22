package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.data.local.db.BookDuration
import com.calypsan.listenup.client.data.local.db.ListeningEventDao
import com.calypsan.listenup.client.data.local.db.ListeningEventEntity
import com.calypsan.listenup.client.domain.model.BookListeningDuration
import com.calypsan.listenup.client.domain.model.ListeningEvent
import com.calypsan.listenup.client.domain.repository.ListeningEventRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room-backed implementation of [ListeningEventRepository].
 *
 * Read-only: listening-event writes are owned by the canonical P2 recording path
 * ([com.calypsan.listenup.client.playback.ListeningEventRecorder]). This class
 * surfaces DAO read queries as domain types.
 *
 * @param listeningEventDao Room DAO for listening event operations.
 */
internal class ListeningEventRepositoryImpl(
    private val listeningEventDao: ListeningEventDao,
) : ListeningEventRepository {
    // ==================== Read methods ====================

    override fun observeEventsForBook(bookId: String): Flow<List<ListeningEvent>> =
        listeningEventDao.observeEventsForBook(bookId).map { entities -> entities.map { it.toDomain() } }

    override fun observeEventsInRange(
        startMs: Long,
        endMs: Long,
    ): Flow<List<ListeningEvent>> =
        listeningEventDao.observeEventsInRange(startMs, endMs).map { entities -> entities.map { it.toDomain() } }

    override fun observeEventsSince(startMs: Long): Flow<List<ListeningEvent>> =
        listeningEventDao.observeEventsSince(startMs).map { entities -> entities.map { it.toDomain() } }

    override suspend fun getTotalDurationSince(startMs: Long): Long = listeningEventDao.getTotalDurationSince(startMs)

    override fun observeTotalDurationSince(startMs: Long): Flow<Long> =
        listeningEventDao.observeTotalDurationSince(startMs)

    override fun observeDistinctBooksSince(startMs: Long): Flow<Int> =
        listeningEventDao.observeDistinctBooksSince(startMs)

    override fun observeDistinctDaysSince(startMs: Long): Flow<List<Long>> =
        listeningEventDao.observeDistinctDaysSince(startMs)

    override suspend fun getDistinctDaysWithActivity(startMs: Long): List<Long> =
        listeningEventDao.getDistinctDaysWithActivity(startMs)

    override suspend fun getDurationByBook(
        startMs: Long,
        endMs: Long,
    ): List<BookListeningDuration> = listeningEventDao.getDurationByBook(startMs, endMs).map { it.toDomain() }
}

// ==================== Mapping functions ====================

/**
 * Maps a [ListeningEventEntity] from the data layer to a [ListeningEvent] domain model.
 *
 * Internal so commonTest can reuse this mapping without duplicating it in fakes.
 */
internal fun ListeningEventEntity.toDomain(): ListeningEvent =
    ListeningEvent(
        id = id,
        userId = userId,
        bookId = bookId,
        startPositionMs = startPositionMs,
        endPositionMs = endPositionMs,
        startedAt = startedAt,
        endedAt = endedAt,
        playbackSpeed = playbackSpeed,
        tz = tz,
        deviceLabel = deviceLabel,
    )

/**
 * Maps a [BookDuration] DAO result to a [BookListeningDuration] domain model.
 *
 * Internal so commonTest can reuse this mapping without duplicating it in fakes.
 */
internal fun BookDuration.toDomain(): BookListeningDuration =
    BookListeningDuration(
        bookId = bookId,
        totalMs = totalMs,
    )
