package com.calypsan.listenup.client.util

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Tests for [relativeOrMonthYear].
 *
 * Fixed reference: nowMs = 2026-06-01T12:00:00 UTC = 1780315200000L.
 *
 * Cases:
 * - same instant → "today"
 * - ~26h ago (1780221600000L) → "yesterday"
 * - 21 days ago (1778500800000L) → "3 weeks ago"
 * - 60 days ago (1775131200000L, landing in April 2026) → "April 2026"
 */
class RelativeOrMonthYearTest :
    FunSpec({

        val nowMs = 1780315200000L // 2026-06-01T12:00:00 UTC

        test("same instant returns today") {
            relativeOrMonthYear(finishedAtMs = nowMs, nowMs = nowMs) shouldBe "today"
        }

        test("~26 hours ago returns yesterday") {
            val yesterdayMs = 1780221600000L // 2026-05-31T10:00:00 UTC, 26h before nowMs
            relativeOrMonthYear(finishedAtMs = yesterdayMs, nowMs = nowMs) shouldBe "yesterday"
        }

        test("7 days ago returns 1 week ago") {
            val oneWeekMs = 1779710400000L // 2026-05-25T12:00:00 UTC, exactly 7 days before nowMs
            relativeOrMonthYear(finishedAtMs = oneWeekMs, nowMs = nowMs) shouldBe "1 week ago"
        }

        test("21 days ago returns 3 weeks ago") {
            val threeWeeksMs = 1778500800000L // 2026-05-11T12:00:00 UTC, exactly 21 days before nowMs
            relativeOrMonthYear(finishedAtMs = threeWeeksMs, nowMs = nowMs) shouldBe "3 weeks ago"
        }

        test("60 days ago returns Month Year") {
            val sixtyDaysMs = 1775131200000L // 2026-04-02T12:00:00 UTC, 60 days before nowMs
            relativeOrMonthYear(finishedAtMs = sixtyDaysMs, nowMs = nowMs) shouldBe "April 2026"
        }
    })
