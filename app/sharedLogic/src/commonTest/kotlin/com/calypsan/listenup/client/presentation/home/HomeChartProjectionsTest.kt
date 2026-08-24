package com.calypsan.listenup.client.presentation.home

import com.calypsan.listenup.client.domain.DayBucket
import com.calypsan.listenup.client.domain.GenreShare
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate

/**
 * The Home chart projections, pinned because every failure mode here is silent: the bars still
 * draw, they are just against the wrong day or the wrong denominator.
 */
class HomeChartProjectionsTest :
    FunSpec({

        // Wednesday. Chosen so the expected labels are not palindromic and a reversal shows up.
        val wednesday = LocalDate(2026, 8, 19)

        fun week(vararg seconds: Long) = seconds.mapIndexed { offset, s -> DayBucket(offset, s) }

        test("today is the LAST column, not the first") {
            val columns = weekChartColumns(week(10, 20, 30, 40, 50, 60, 70), wednesday)

            columns.last().isToday shouldBe true
            columns.first().isToday shouldBe false
            columns.last().totalSeconds shouldBe 10
        }

        test("labels run oldest to newest, ending on today's weekday") {
            val columns = weekChartColumns(week(1, 1, 1, 1, 1, 1, 1), wednesday)

            // Six days before Wednesday is Thursday; the week reads T F S S M T W.
            columns.map { it.label } shouldBe listOf("T", "F", "S", "S", "M", "T", "W")
        }

        test("a day's own offset decides isToday, so a short week still marks the right column") {
            // Only three buckets, and today is not at either end of the input order.
            val columns =
                weekChartColumns(
                    listOf(DayBucket(2, 5), DayBucket(0, 9), DayBucket(1, 7)),
                    wednesday,
                )

            columns.single { it.isToday }.totalSeconds shouldBe 9
        }

        test("genre percentages are a share of the genres SHOWN, so they sum to about 100") {
            val bars =
                genreShareBars(
                    listOf(
                        GenreShare("Fiction", 3),
                        GenreShare("Sci-Fi", 2),
                        GenreShare("Mystery", 1),
                    ),
                )

            // Raw seconds are 3/2/1 — nothing near a percentage — so this only passes if the
            // seconds are normalised against the sum of the three, not passed through.
            bars.map { it.percent } shouldBe listOf(50, 33, 16)
        }

        test("all-zero listening yields zero bars rather than dividing by zero") {
            val bars = genreShareBars(listOf(GenreShare("Fiction", 0), GenreShare("Sci-Fi", 0)))

            bars.map { it.percent } shouldBe listOf(0, 0)
        }

        test("no genres yields no bars") {
            genreShareBars(emptyList()) shouldBe emptyList()
        }
    })
