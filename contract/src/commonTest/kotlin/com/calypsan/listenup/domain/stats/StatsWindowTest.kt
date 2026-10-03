package com.calypsan.listenup.domain.stats

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Instant
import kotlinx.datetime.TimeZone

private val edmonton = TimeZone.of("America/Edmonton")

private fun ms(iso: String): Long = Instant.parse(iso).toEpochMilliseconds()

class StatsWindowTest :
    FunSpec({

        test("the week starts at local midnight six days before today") {
            // 2026-10-02 10:20 MDT → 2026-09-26 00:00 MDT.
            StatsWindow.Week.startMs(Instant.parse("2026-10-02T16:20:00Z"), edmonton) shouldBe
                ms("2026-09-26T06:00:00Z")
        }

        test("the month is today plus the previous 29 days") {
            StatsWindow.Month.startMs(Instant.parse("2026-10-02T16:20:00Z"), edmonton) shouldBe
                ms("2026-09-03T06:00:00Z")
        }

        test("the year is today plus the previous 364 days") {
            StatsWindow.Year.startMs(Instant.parse("2026-10-02T16:20:00Z"), edmonton) shouldBe
                ms("2025-10-03T06:00:00Z")
        }

        test("in UTC the week starts at UTC midnight") {
            StatsWindow.Week.startMs(Instant.parse("2026-10-02T16:20:00Z"), TimeZone.UTC) shouldBe
                ms("2026-09-26T00:00:00Z")
        }

        test("a window spanning spring-forward starts at that day's local midnight (MST)") {
            // 2026-03-10 12:00 MDT; DST began 2026-03-08 → 2026-03-04 00:00 MST = 07:00Z.
            StatsWindow.Week.startMs(Instant.parse("2026-03-10T18:00:00Z"), edmonton) shouldBe
                ms("2026-03-04T07:00:00Z")
        }

        test("a window spanning fall-back starts at that day's local midnight (MDT)") {
            // 2026-11-03 11:00 MST; DST ended 2026-11-01 → 2026-10-28 00:00 MDT = 06:00Z.
            StatsWindow.Week.startMs(Instant.parse("2026-11-03T18:00:00Z"), edmonton) shouldBe
                ms("2026-10-28T06:00:00Z")
        }

        test("the week rolls over exactly at local midnight") {
            val justBefore = Instant.parse("2026-10-03T05:59:59Z") // Oct 2 23:59:59 MDT
            val justAfter = Instant.parse("2026-10-03T06:00:00Z") // Oct 3 00:00 MDT
            StatsWindow.Week.startMs(justBefore, edmonton) shouldBe ms("2026-09-26T06:00:00Z")
            StatsWindow.Week.startMs(justAfter, edmonton) shouldBe ms("2026-09-27T06:00:00Z")
        }

        test("a span that ends inside the window counts whole, even if it began before it") {
            val start = ms("2026-09-26T06:00:00Z")
            StatsWindow.secondsCounted(
                startedAtMs = start - 20 * 60_000L,
                endedAtMs = start + 20 * 60_000L,
                windowStartMs = start,
            ) shouldBe 40 * 60L
        }

        test("a span that ended before the window counts nothing") {
            val start = ms("2026-09-26T06:00:00Z")
            StatsWindow.secondsCounted(
                startedAtMs = start - 60_000L,
                endedAtMs = start - 1L,
                windowStartMs = start,
            ) shouldBe 0L
        }
    })
