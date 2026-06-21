package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.core.suspendRunCatching
import com.calypsan.listenup.client.data.local.db.BookDuration
import com.calypsan.listenup.client.data.local.db.ListeningEventDao
import com.calypsan.listenup.client.data.local.db.ListeningEventEntity
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.domain.model.BookListeningDuration
import com.calypsan.listenup.client.domain.model.ListeningEvent
import com.calypsan.listenup.client.domain.repository.ListeningEventRepository
import com.calypsan.listenup.client.util.NanoId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room-backed implementation of [ListeningEventRepository].
 *
 * The write path wraps the DAO upsert inside [TransactionRunner.atomically].
 * The pending-op enqueue is **not** part of this transaction — the P2 canonical
 * recording path ([com.calypsan.listenup.client.playback.ListeningEventRecorder])
 * handles that directly.
 *
 * [suspendRunCatching] handles [kotlinx.coroutines.CancellationException] rethrow
 * automatically (EM-R1).
 *
 * @param listeningEventDao Room DAO for listening event operations.
 * @param transactionRunner Wraps the DAO upsert in a single DB transaction.
 * @param userId Authenticated user ID injected from the DI graph.
 * @param tz IANA timezone name injected from the DI graph (e.g. `"Europe/London"`).
 * @param deviceLabel Human-readable device label (null if unavailable).
 */
internal class ListeningEventRepositoryImpl(
    private val listeningEventDao: ListeningEventDao,
    private val transactionRunner: TransactionRunner,
    private val userId: String,
    private val tz: String,
    private val deviceLabel: String?,
) : ListeningEventRepository {
    override suspend fun queueListeningEvent(
        bookId: BookId,
        startPositionMs: Long,
        endPositionMs: Long,
        startedAt: Long,
        endedAt: Long,
        playbackSpeed: Float,
    ): AppResult<Unit> =
        suspendRunCatching {
            val eventId = NanoId.generate("evt")

            val entity =
                ListeningEventEntity(
                    id = eventId,
                    userId = userId,
                    bookId = bookId.value,
                    startPositionMs = startPositionMs,
                    endPositionMs = endPositionMs,
                    startedAt = startedAt,
                    endedAt = endedAt,
                    playbackSpeed = playbackSpeed,
                    tz = tz,
                    deviceLabel = deviceLabel,
                )

            transactionRunner.atomically {
                listeningEventDao.upsert(entity)
            }
        }

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
