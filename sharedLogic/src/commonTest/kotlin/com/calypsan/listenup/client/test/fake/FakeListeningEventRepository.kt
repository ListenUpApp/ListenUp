package com.calypsan.listenup.client.test.fake

import com.calypsan.listenup.client.data.local.db.ListeningEventEntity
import com.calypsan.listenup.client.data.repository.toDomain
import com.calypsan.listenup.client.domain.model.BookListeningDuration
import com.calypsan.listenup.client.domain.model.ListeningEvent
import com.calypsan.listenup.client.domain.repository.ListeningEventRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory fake of the read-only [ListeningEventRepository].
 *
 * Backed by a [MutableStateFlow] of [ListeningEventEntity] so reactive
 * `observe*` methods emit on every [setEvents] call — matching the
 * read-after-write semantics of the Room-backed implementation.
 *
 * Mapping from entity to domain is delegated to the canonical `toDomain()` extension
 * defined in [com.calypsan.listenup.client.data.repository.ListeningEventRepositoryImpl].
 */
internal class FakeListeningEventRepository(
    initialEvents: List<ListeningEventEntity> = emptyList(),
) : ListeningEventRepository {
    private val events = MutableStateFlow(initialEvents)

    override fun observeEventsForBook(bookId: String): Flow<List<ListeningEvent>> =
        events.asStateFlow().map { list -> list.filter { it.bookId == bookId }.map { it.toDomain() } }

    override fun observeEventsInRange(
        startMs: Long,
        endMs: Long,
    ): Flow<List<ListeningEvent>> =
        events.asStateFlow().map { list -> list.filter { it.endedAt in startMs..<endMs }.map { it.toDomain() } }

    override fun observeEventsSince(startMs: Long): Flow<List<ListeningEvent>> =
        events.asStateFlow().map { list -> list.filter { it.endedAt >= startMs }.map { it.toDomain() } }

    override suspend fun getTotalDurationSince(startMs: Long): Long =
        events.value
            .filter { it.endedAt >= startMs }
            .sumOf { it.endPositionMs - it.startPositionMs }

    override fun observeTotalDurationSince(startMs: Long): Flow<Long> =
        events.asStateFlow().map { list ->
            list.filter { it.endedAt >= startMs }.sumOf { it.endPositionMs - it.startPositionMs }
        }

    override fun observeDistinctBooksSince(startMs: Long): Flow<Int> =
        events.asStateFlow().map { list ->
            list
                .filter { it.endedAt >= startMs }
                .map { it.bookId }
                .toSet()
                .size
        }

    override fun observeDistinctDaysSince(startMs: Long): Flow<List<Long>> =
        events.asStateFlow().map { list ->
            list
                .filter { it.endedAt >= startMs }
                .map { (it.endedAt / 86_400_000L) }
                .toSet()
                .sortedDescending()
        }

    override suspend fun getDistinctDaysWithActivity(startMs: Long): List<Long> =
        events.value
            .filter { it.endedAt >= startMs }
            .map { (it.endedAt / 86_400_000L) * 86_400_000L }
            .toSet()
            .sortedDescending()

    override suspend fun getDurationByBook(
        startMs: Long,
        endMs: Long,
    ): List<BookListeningDuration> =
        events.value
            .filter { it.endedAt in startMs..<endMs }
            .groupBy { it.bookId }
            .map { (bookId, es) ->
                BookListeningDuration(
                    bookId = bookId,
                    totalMs =
                        es.sumOf {
                            it.endPositionMs -
                                it.startPositionMs
                        },
                )
            }.sortedByDescending { it.totalMs }

    /** Test helper: replace all events and emit to all observers. */
    fun setEvents(list: List<ListeningEventEntity>) {
        events.value = list
    }
}
