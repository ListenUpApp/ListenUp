@file:OptIn(ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.data.local.db.ListeningEventDao
import com.calypsan.listenup.client.data.local.db.ListeningEventEntity
import com.calypsan.listenup.client.domain.history.BookListeningHistory
import com.calypsan.listenup.client.domain.history.DayBucket
import com.calypsan.listenup.client.domain.history.EventEntry
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.BookListeningHistoryRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime

private val MONTH_LABELS =
    mapOf(
        "JANUARY" to "Jan",
        "FEBRUARY" to "Feb",
        "MARCH" to "Mar",
        "APRIL" to "Apr",
        "MAY" to "May",
        "JUNE" to "Jun",
        "JULY" to "Jul",
        "AUGUST" to "Aug",
        "SEPTEMBER" to "Sep",
        "OCTOBER" to "Oct",
        "NOVEMBER" to "Nov",
        "DECEMBER" to "Dec",
    )

/**
 * [BookListeningHistoryRepository] that derives day-grouped per-book history from
 * the local `listening_events` Room table.
 *
 * Day boundaries use each event's own recorded [ListeningEventEntity.tz] (the IANA
 * timezone name saved at event time). This means an event recorded at 23:30 in
 * London lands in the London calendar day, even if the viewer is in New York.
 * If [ListeningEventEntity.tz] is unparseable the viewer's current timezone is used
 * as a fallback so the event is never silently dropped.
 *
 * Relative labels ("Today", "Yesterday", month+day) are computed against the
 * viewer's current local date so the timeline reads naturally on the device.
 *
 * @param listeningEventDao DAO for reactive per-book queries.
 * @param authSession Source of the current user's auth state.
 * @param clock Injected for deterministic testing; defaults to [Clock.System].
 * @param viewerTimeZone Factory for the viewer's current timezone; defaults to
 *   the system default. Injected for deterministic timezone testing.
 */
class BookListeningHistoryRepositoryImpl(
    private val listeningEventDao: ListeningEventDao,
    private val authSession: AuthSession,
    private val clock: Clock = Clock.System,
    private val viewerTimeZone: () -> TimeZone = { TimeZone.currentSystemDefault() },
) : BookListeningHistoryRepository {
    override fun observeFor(bookId: String): Flow<BookListeningHistory> =
        authSession.authState
            .map { state ->
                when (state) {
                    is AuthState.Authenticated -> state.userId.value
                    else -> null
                }
            }.flatMapLatest { userId ->
                if (userId == null) return@flatMapLatest flowOf(BookListeningHistory(emptyList()))
                listeningEventDao
                    .observeByBookForUser(userId, bookId)
                    .map { events -> groupByDay(events) }
            }

    private fun groupByDay(events: List<ListeningEventEntity>): BookListeningHistory {
        val viewerTz = viewerTimeZone()
        val today = clock.now().toLocalDateTime(viewerTz).date
        val yesterday = today.minus(DatePeriod(days = 1))

        val byDate: Map<LocalDate, List<ListeningEventEntity>> =
            events.groupBy { event ->
                val eventTz = runCatching { TimeZone.of(event.tz) }.getOrDefault(viewerTz)
                Instant.fromEpochMilliseconds(event.endedAt).toLocalDateTime(eventTz).date
            }

        val daily =
            byDate.entries
                .sortedByDescending { it.key }
                .map { (date, dayEvents) ->
                    DayBucket(
                        date = date,
                        relativeLabel = relativeLabel(date, today, yesterday),
                        totalSeconds = dayEvents.sumOf { (it.endedAt - it.startedAt) / 1000L },
                        events =
                            dayEvents
                                .sortedByDescending { it.endedAt }
                                .map { it.toEventEntry() },
                    )
                }
        return BookListeningHistory(daily)
    }

    private fun relativeLabel(
        date: LocalDate,
        today: LocalDate,
        yesterday: LocalDate,
    ): String =
        when (date) {
            today -> "Today"
            yesterday -> "Yesterday"
            else -> formatDate(date, today)
        }

    private fun formatDate(
        date: LocalDate,
        today: LocalDate,
    ): String {
        val month =
            MONTH_LABELS[date.month.name] ?: date.month.name
                .lowercase()
                .replaceFirstChar { it.uppercase() }
                .take(3)
        return if (date.year == today.year) {
            "$month ${date.day}"
        } else {
            "$month ${date.day}, ${date.year}"
        }
    }

    private fun ListeningEventEntity.toEventEntry() =
        EventEntry(
            id = id,
            startedAt = startedAt,
            endedAt = endedAt,
            startPositionMs = startPositionMs,
            endPositionMs = endPositionMs,
            playbackSpeed = playbackSpeed,
            deviceLabel = deviceLabel,
        )
}
