package com.calypsan.listenup.server.api

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

class HardcoverReadPairingTest :
    FunSpec({
        fun noon(date: String): Long = LocalDateTime.parse("${date}T12:00").toInstant(TimeZone.UTC).toEpochMilliseconds()

        test("the closest pair is taken first, whatever order the finishes arrive in") {
            val early = noon("2026-10-01")
            val late = noon("2026-10-04")
            val read = noon("2026-10-03")

            pairWithHardcover(own = listOf(early, late), hardcover = listOf(read), zone = TimeZone.UTC) shouldBe
                PairedReads(alsoOnHardcover = listOf(late), hardcoverOnly = emptyList())
        }

        test("two reads equally near one finish: the earlier pairs, the later stays a Hardcover read") {
            val listened = noon("2026-10-02")
            val before = noon("2026-10-01")
            val after = noon("2026-10-03")

            pairWithHardcover(own = listOf(listened), hardcover = listOf(after, before), zone = TimeZone.UTC) shouldBe
                PairedReads(alsoOnHardcover = listOf(listened), hardcoverOnly = listOf(after))
        }

        test("each finish and each read pairs once at most") {
            val listened = noon("2026-10-02")
            val read = noon("2026-10-02")

            pairWithHardcover(own = listOf(listened, listened), hardcover = listOf(read), zone = TimeZone.UTC) shouldBe
                PairedReads(alsoOnHardcover = listOf(listened), hardcoverOnly = emptyList())
        }
    })
