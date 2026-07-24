package com.calypsan.listenup.client.core

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * The series-starter heuristic: standalones, blanks,
 * prequels (0 / 0.5), and "1"-family sequences are starters; "2"+ are mid-series.
 */
class SeriesSequenceTest :
    FunSpec({
        test("isFirstInSeries matches the Go starter heuristic") {
            val cases =
                listOf(
                    null to true,
                    "" to true,
                    "   " to true,
                    "0" to true,
                    "0.5" to true,
                    "1" to true,
                    "01" to true,
                    "001" to true,
                    "1.0" to true,
                    "1.5" to true,
                    "Book 1" to true,
                    "Prequel" to true,
                    "2" to false,
                    "2.5" to false,
                    "Book 2" to false,
                    "10" to false,
                    "11" to false,
                )
            for ((input, expected) in cases) {
                withClue("isFirstInSeries(${input?.let { "\"$it\"" } ?: "null"}) should be $expected") {
                    isFirstInSeries(input) shouldBe expected
                }
            }
        }
    })
