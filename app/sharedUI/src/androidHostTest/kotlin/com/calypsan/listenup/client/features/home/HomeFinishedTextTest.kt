package com.calypsan.listenup.client.features.home

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/** The finish card's day counting and dates, by the reader's local calendar (C10). */
class HomeFinishedTextTest :
    FunSpec({
        fun at(
            zone: TimeZone,
            text: String,
        ): Long = LocalDateTime.parse(text).toInstant(zone).toEpochMilliseconds()

        test("days are counted by calendar, not by 24-hour blocks") {
            val utc = TimeZone.UTC
            val now = at(utc, "2025-10-10T00:30")
            finishedDaysAgo(at(utc, "2025-10-10T00:05"), now, utc) shouldBe 0
            finishedDaysAgo(at(utc, "2025-10-09T23:55"), now, utc) shouldBe 1
            finishedDaysAgo(at(utc, "2025-10-08T01:00"), now, utc) shouldBe 2
            finishedDaysAgo(at(utc, "2025-10-07T12:00"), now, utc) shouldBe 3
        }

        test("a day boundary across the clocks going back still counts one day") {
            val london = TimeZone.of("Europe/London")
            // BST ends 2025-10-26 02:00.
            finishedDaysAgo(at(london, "2025-10-25T23:30"), at(london, "2025-10-26T09:00"), london) shouldBe 1
            finishedDaysAgo(at(london, "2025-10-26T00:30"), at(london, "2025-10-26T23:00"), london) shouldBe 0
        }

        test("the range reads day then short month") {
            shortDayMonth(at(TimeZone.UTC, "2025-09-06T12:00"), TimeZone.UTC) shouldBe "6 Sep"
            shortDayMonth(at(TimeZone.UTC, "2025-10-10T11:00"), TimeZone.UTC) shouldBe "10 Oct"
        }
    })
