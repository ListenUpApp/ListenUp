package com.calypsan.listenup.client.presentation.bookdetail

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * The two days a "Mark as finished" form asks for: when the reader started and when they finished.
 *
 * Calendar days, not instants, because that is what a person remembers about a book they read
 * before ListenUp existed — and because holding instants is how the form used to go wrong. A day
 * chosen in a date picker and stored as UTC midnight reads back as the day before anywhere west of
 * Greenwich; a day held as a [LocalDate] and turned into an instant only at the edge, in the
 * reader's own zone, cannot.
 *
 * Android and web share this; iOS mirrors it with `Calendar` because a [LocalDate] does not cross
 * the Swift Export boundary usefully.
 */
data class FinishDates(
    val started: LocalDate,
    val finished: LocalDate,
) {
    /** Why these dates cannot be saved, or null when they can. [today] is the reader's local date. */
    fun problem(today: LocalDate): FinishDatesProblem? =
        when {
            started > today || finished > today -> FinishDatesProblem.InTheFuture
            finished < started -> FinishDatesProblem.FinishedBeforeStarted
            else -> null
        }

    /**
     * The epoch milliseconds `BookDetailViewModel.markComplete` takes.
     *
     * A day the reader left alone keeps the instant it was opened with — the recorded start, or
     * now — so confirming without editing sends exactly what the one-tap finish always did. A day
     * they changed becomes the start of that day in [timeZone]. The finish is never earlier than the
     * start: finishing on the day a book was started at 11:00 must not record 00:00 that day.
     */
    fun toTimestamps(
        startedAtMs: Long?,
        nowMs: Long,
        timeZone: TimeZone,
    ): FinishTimestamps {
        val opened = initial(startedAtMs, nowMs, timeZone)
        val startMs = if (started == opened.started) startedAtMs ?: nowMs else started.startMs(timeZone)
        val finishMs = if (finished == opened.finished) nowMs else finished.startMs(timeZone)
        return FinishTimestamps(startedAtMs = startMs, finishedAtMs = maxOf(finishMs, startMs))
    }

    /** How the form opens. */
    companion object {
        /** Started on the recorded start day (today if there is none), finished today. */
        fun initial(
            startedAtMs: Long?,
            nowMs: Long,
            timeZone: TimeZone,
        ): FinishDates =
            FinishDates(
                started = dayOf(startedAtMs ?: nowMs, timeZone),
                finished = dayOf(nowMs, timeZone),
            )

        /** The calendar day [epochMs] falls on in [timeZone]. */
        fun dayOf(
            epochMs: Long,
            timeZone: TimeZone,
        ): LocalDate = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(timeZone).date
    }
}

/** The instants a [FinishDates] resolves to, in epoch milliseconds. */
data class FinishTimestamps(
    val startedAtMs: Long,
    val finishedAtMs: Long,
)

/** What is wrong with a [FinishDates] that cannot be saved. */
enum class FinishDatesProblem {
    /** The finish day is earlier than the start day. */
    FinishedBeforeStarted,

    /** Either day is after today. */
    InTheFuture,
}

private fun LocalDate.startMs(timeZone: TimeZone): Long = atStartOfDayIn(timeZone).toEpochMilliseconds()
