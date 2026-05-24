@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.client.domain.leaderboard

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.TimeZone

class LeaderboardPeriodTest :
    FunSpec({
        val now = Instant.parse("2026-05-23T15:30:00Z") // Saturday afternoon UTC
        val london = TimeZone.of("Europe/London") // BST = UTC+1
        val newYork = TimeZone.of("America/New_York") // EDT = UTC-4

        test("Week.bounds covers 7 days ending now") {
            val (start, end) = LeaderboardPeriod.Week.bounds(now, london)
            end shouldBe now.toEpochMilliseconds()
            // 2026-05-23 in London BST is 2026-05-23T00:00 BST = 2026-05-22T23:00Z
            // minus 6 days → 2026-05-17T00:00 BST = 2026-05-16T23:00Z
            start shouldBe Instant.parse("2026-05-16T23:00:00Z").toEpochMilliseconds()
        }

        test("Week.bounds differs by TZ — same Instant, different start-of-day") {
            val (startLondon, _) = LeaderboardPeriod.Week.bounds(now, london)
            val (startNY, _) = LeaderboardPeriod.Week.bounds(now, newYork)
            startLondon shouldNotBe startNY
        }

        test("Month.bounds covers current month") {
            val (start, end) = LeaderboardPeriod.Month.bounds(now, london)
            end shouldBe now.toEpochMilliseconds()
            // 2026-05-01T00:00 London BST = 2026-04-30T23:00Z
            start shouldBe Instant.parse("2026-04-30T23:00:00Z").toEpochMilliseconds()
        }

        test("Year.bounds covers current year") {
            val (start, _) = LeaderboardPeriod.Year.bounds(now, london)
            // 2026-01-01T00:00 London GMT (no DST in Jan) = 2026-01-01T00:00Z
            start shouldBe Instant.parse("2026-01-01T00:00:00Z").toEpochMilliseconds()
        }

        test("AllTime.bounds is the full epoch window") {
            val (start, end) = LeaderboardPeriod.AllTime.bounds(now, london)
            start shouldBe 0L
            end shouldBe Long.MAX_VALUE
        }
    })
