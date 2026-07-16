package com.calypsan.listenup.client.test.fake

import com.calypsan.listenup.api.dto.world.WorldEventUpsert
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.api.sync.WorldEventSource
import com.calypsan.listenup.client.domain.model.NewWorldEvent
import com.calypsan.listenup.client.domain.model.WorldEvent
import com.calypsan.listenup.client.domain.repository.WorldEventEditRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory fake of [WorldEventEditRepository]. Backed by a [MutableStateFlow] of the full event
 * list so observers re-emit on every [setEvents] call.
 *
 * Tests control ordering directly via [setEvents] — the real repository's `observeForWorld`
 * emits recency-sorted (`updatedAt` DESC), a column this fake's [WorldEvent] domain shape doesn't
 * carry; callers seed the list in the order they want observers to see.
 */
class FakeWorldEventEditRepository(
    initialEvents: List<WorldEvent> = emptyList(),
) : WorldEventEditRepository {
    private val events = MutableStateFlow(initialEvents)

    override fun observeForBook(bookId: String): Flow<List<WorldEvent>> =
        events.asStateFlow().map { list -> list.filter { it.bookId == bookId } }

    override fun observeForEntity(entityId: String): Flow<List<WorldEvent>> =
        events.asStateFlow().map { list ->
            list.filter { it.subjectEntityId == entityId || it.objectEntityId == entityId || entityId in it.mentionIds }
        }

    override fun observeForWorld(
        homeSeriesId: String?,
        homeBookId: String?,
    ): Flow<List<WorldEvent>> =
        events.asStateFlow().map { list ->
            list.filter { it.homeSeriesId == homeSeriesId && it.homeBookId == homeBookId }
        }

    override fun observeEvent(id: String): Flow<WorldEvent?> =
        events.asStateFlow().map { list -> list.find { it.id == id } }

    override suspend fun record(event: NewWorldEvent): AppResult<String> =
        recordBatch(listOf(event)).map { it.single() }

    override suspend fun recordBatch(events: List<NewWorldEvent>): AppResult<List<String>> {
        val startIndex = this.events.value.size
        val minted =
            events.mapIndexed { index, newEvent ->
                WorldEvent(
                    id = "fake-event-${startIndex + index}",
                    homeSeriesId = newEvent.homeSeriesId,
                    homeBookId = newEvent.homeBookId,
                    bookId = newEvent.bookId,
                    positionMs = newEvent.positionMs,
                    type = newEvent.type,
                    text = newEvent.text,
                    subjectEntityId = newEvent.subjectEntityId,
                    objectEntityId = newEvent.objectEntityId,
                    source = WorldEventSource.MANUAL,
                )
            }
        this.events.value = this.events.value + minted
        return AppResult.Success(minted.map { it.id })
    }

    override suspend fun update(upsert: WorldEventUpsert): AppResult<Unit> {
        events.value =
            events.value.map { existing ->
                if (existing.id == upsert.id) {
                    existing.copy(
                        homeSeriesId = upsert.homeSeriesId,
                        homeBookId = upsert.homeBookId,
                        bookId = upsert.bookId,
                        positionMs = upsert.positionMs,
                        type = upsert.type,
                        text = upsert.text,
                        subjectEntityId = upsert.subjectEntityId,
                        objectEntityId = upsert.objectEntityId,
                    )
                } else {
                    existing
                }
            }
        return AppResult.Success(Unit)
    }

    override suspend fun delete(id: String): AppResult<Unit> {
        events.value = events.value.filterNot { it.id == id }
        return AppResult.Success(Unit)
    }

    /** Test helper: replace the event list, emitting to all observers. */
    fun setEvents(list: List<WorldEvent>) {
        events.value = list
    }
}
