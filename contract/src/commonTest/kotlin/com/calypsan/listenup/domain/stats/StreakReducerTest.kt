package com.calypsan.listenup.domain.stats

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.random.Random
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus

private val today = LocalDate(2026, 7, 2)

private fun daysAgo(n: Int): LocalDate = today.minus(DatePeriod(days = n))

class StreakReducerTest :
    FunSpec({

        test("empty history yields zero current and longest") {
            StreakReducer.reduce(emptyList(), today) shouldBe Streaks(current = 0, longest = 0)
        }

        test("a single listen today is a current and longest streak of 1") {
            StreakReducer.reduce(listOf(today), today) shouldBe Streaks(current = 1, longest = 1)
        }

        test("current streak anchors on yesterday when today has no listen yet") {
            StreakReducer.reduce(listOf(daysAgo(2), daysAgo(1)), today) shouldBe Streaks(current = 2, longest = 2)
        }

        test("current streak is 0 once the last listen is older than yesterday (lapsed)") {
            // A 3-day run ending 3 days ago: longest survives, current has lapsed.
            val result = StreakReducer.reduce(listOf(daysAgo(5), daysAgo(4), daysAgo(3)), today)
            result shouldBe Streaks(current = 0, longest = 3)
        }

        test("multiple listens on the same day collapse to one streak day") {
            val result = StreakReducer.reduce(listOf(today, today, today, daysAgo(1)), today)
            result shouldBe Streaks(current = 2, longest = 2)
        }

        test("a gap restarts the current run but longest remembers the best past run") {
            // 4-day run ending 6 days ago, then a fresh 2-day run ending today.
            val days =
                listOf(daysAgo(9), daysAgo(8), daysAgo(7), daysAgo(6)) + listOf(daysAgo(1), today)
            StreakReducer.reduce(days, today) shouldBe Streaks(current = 2, longest = 4)
        }

        test("output is independent of input order (shuffled input yields identical result)") {
            // A deterministic mixed history: two runs plus a duplicate day.
            val sorted =
                listOf(daysAgo(10), daysAgo(9), daysAgo(8)) + // past 3-day run
                    listOf(daysAgo(2), daysAgo(1), today, today) // current 3-day run with a dupe
            val expected = StreakReducer.reduce(sorted, today)
            expected shouldBe Streaks(current = 3, longest = 3)

            // Every shuffle of the same multiset must reduce to the same Streaks.
            val rng = Random(seed = 20260702)
            repeat(50) {
                StreakReducer.reduce(sorted.shuffled(rng), today) shouldBe expected
            }
        }

        test("today extends a run started earlier") {
            val days = (1..4).map { daysAgo(it) } + today // daysAgo 4,3,2,1 + today = 5 consecutive
            StreakReducer.reduce(days, today) shouldBe Streaks(current = 5, longest = 5)
        }

        test("a future-dated day beyond today does not count toward the current run") {
            // Defensive: a day after `today` is not `today`/`today-1`, so the anchor rule leaves
            // current at 0 (the most-recent day is in the future). longest still reflects the run.
            val tomorrow = today.plus(DatePeriod(days = 1))
            val result = StreakReducer.reduce(listOf(daysAgo(1), today, tomorrow), today)
            result.longest shouldBe 3
            result.current shouldBe 0
        }
    })
