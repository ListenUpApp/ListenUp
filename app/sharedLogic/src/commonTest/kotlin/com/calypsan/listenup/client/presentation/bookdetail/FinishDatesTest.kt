package com.calypsan.listenup.client.presentation.bookdetail

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.datetime.FixedOffsetTimeZone
import kotlinx.datetime.LocalDate
import kotlinx.datetime.UtcOffset
import kotlin.time.Instant

/**
 * The dates "Mark as finished" asks for, on every platform.
 *
 * Pinned in a fixed UTC−7 zone because the bug this shape exists to prevent is a timezone one: a
 * calendar day stored as UTC midnight reads back as the day BEFORE anywhere west of Greenwich.
 */
class FinishDatesTest :
    FunSpec({
        val pacific = FixedOffsetTimeZone(UtcOffset(hours = -7))

        // 2026-09-30T03:00Z is still the evening of the 29th in UTC−7.
        val nowMs = Instant.parse("2026-09-30T03:00:00Z").toEpochMilliseconds()
        val localToday = LocalDate(2026, 9, 29)

        test("a book never started opens on today for both, in the reader's own zone") {
            FinishDates.initial(startedAtMs = null, nowMs = nowMs, timeZone = pacific) shouldBe
                FinishDates(started = localToday, finished = localToday)
        }

        test("a book already started opens on the day it was started") {
            val startedAtMs = Instant.parse("2026-09-02T18:00:00Z").toEpochMilliseconds()

            FinishDates.initial(startedAtMs, nowMs, pacific) shouldBe
                FinishDates(started = LocalDate(2026, 9, 2), finished = localToday)
        }

        test("untouched dates claim no start and finish now, as the one-tap finish always did") {
            // A start day the reader left alone is not a day they picked: it claims nothing, so the
            // server keeps dating the read by when they actually started listening in ListenUp.
            val startedAtMs = Instant.parse("2026-09-02T18:00:00Z").toEpochMilliseconds()

            FinishDates.initial(startedAtMs, nowMs, pacific).toTimestamps(startedAtMs, nowMs, pacific) shouldBe
                FinishTimestamps(startedAtMs = null, finishedAtMs = nowMs)
            FinishDates.initial(null, nowMs, pacific).toTimestamps(null, nowMs, pacific) shouldBe
                FinishTimestamps(startedAtMs = null, finishedAtMs = nowMs)
        }

        test("a start day the reader changed is sent, even when the finish was left alone") {
            val startedAtMs = Instant.parse("2026-09-02T18:00:00Z").toEpochMilliseconds()
            val dates = FinishDates(started = LocalDate(2026, 9, 1), finished = localToday)

            dates.toTimestamps(startedAtMs, nowMs, pacific) shouldBe
                FinishTimestamps(
                    startedAtMs = Instant.parse("2026-09-01T07:00:00Z").toEpochMilliseconds(),
                    finishedAtMs = nowMs,
                )
        }

        test("a chosen day is the start of that day where the reader is, not in UTC") {
            val dates = FinishDates(started = LocalDate(2026, 9, 1), finished = LocalDate(2026, 9, 3))

            dates.toTimestamps(startedAtMs = null, nowMs = nowMs, timeZone = pacific) shouldBe
                FinishTimestamps(
                    startedAtMs = Instant.parse("2026-09-01T07:00:00Z").toEpochMilliseconds(),
                    finishedAtMs = Instant.parse("2026-09-03T07:00:00Z").toEpochMilliseconds(),
                )
        }

        test("finishing on the day it was started never records the finish before the start") {
            // Started at 11:00 local on the 2nd; the reader says they finished that same day. The
            // start of the 2nd is earlier than the kept start instant, so the finish meets it.
            val startedAtMs = Instant.parse("2026-09-02T18:00:00Z").toEpochMilliseconds()
            val dates = FinishDates(started = LocalDate(2026, 9, 2), finished = LocalDate(2026, 9, 2))

            dates.toTimestamps(startedAtMs, nowMs, pacific) shouldBe
                FinishTimestamps(startedAtMs = null, finishedAtMs = startedAtMs)
        }

        test("finished before started cannot be saved") {
            FinishDates(started = LocalDate(2026, 9, 10), finished = LocalDate(2026, 9, 9))
                .problem(today = localToday) shouldBe FinishDatesProblem.FinishedBeforeStarted
        }

        test("a finish in the future cannot be saved") {
            FinishDates(started = localToday, finished = LocalDate(2026, 9, 30))
                .problem(today = localToday) shouldBe FinishDatesProblem.InTheFuture
        }

        test("a start in the future cannot be saved, even with the finish after it") {
            FinishDates(started = LocalDate(2026, 10, 1), finished = LocalDate(2026, 10, 2))
                .problem(today = localToday) shouldBe FinishDatesProblem.InTheFuture
        }

        test("started and finished on the same day, today, is fine") {
            FinishDates(started = localToday, finished = localToday).problem(today = localToday).shouldBeNull()
        }

        test("a book read years ago is fine") {
            FinishDates(started = LocalDate(2019, 3, 1), finished = LocalDate(2019, 4, 12))
                .problem(today = localToday)
                .shouldBeNull()
        }
    })
