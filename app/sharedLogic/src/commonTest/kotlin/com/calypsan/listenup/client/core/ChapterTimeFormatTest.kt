package com.calypsan.listenup.client.core

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * [ChapterTimeFormat] — the digits the chapter editor is manipulated by.
 *
 * Shared across Android, iOS and web on purpose: the same boundary must read the same everywhere,
 * and the decisions worth pinning here (hours not wrapping at 24, zero-padding, where the decimal
 * falls) are exactly the ones each platform would otherwise make slightly differently.
 */
class ChapterTimeFormatTest :
    FunSpec({

        // 41:12:08.420 — the working position from the editor's own design data.
        val position = 148_328_420L

        test("the clock reads hours, minutes and seconds") {
            ChapterTimeFormat.clock(position) shouldBe "41:12:08"
        }

        test("HOURS DO NOT WRAP AT 24 — a 65-hour book really is 41 hours in") {
            // Wrapping would address a completely different instant and look plausible doing it.
            ChapterTimeFormat.clock(234_000_000L) shouldBe "65:00:00"
            ChapterTimeFormat.clock(90_061_000L) shouldBe "25:01:01"
        }

        test("minutes and seconds are zero-padded so the digits stop moving") {
            // Columns that jump width as the value changes are unreadable while scrubbing.
            ChapterTimeFormat.clock(3_661_000L) shouldBe "1:01:01"
            ChapterTimeFormat.clock(60_000L) shouldBe "0:01:00"
            ChapterTimeFormat.clock(0L) shouldBe "0:00:00"
        }

        test("precise adds a tenth, exact adds a hundredth") {
            ChapterTimeFormat.precise(position) shouldBe "41:12:08.4"
            ChapterTimeFormat.exact(position) shouldBe "41:12:08.42"
        }

        test("the hundredth is padded, so 41:12:08.05 is not shown as 41:12:08.5") {
            // The bug this prevents is a factor-of-ten error in the readout people snap by.
            ChapterTimeFormat.exact(148_328_050L) shouldBe "41:12:08.05"
            ChapterTimeFormat.precise(148_328_050L) shouldBe "41:12:08.0"
        }

        test("sub-second values keep their leading zeros") {
            ChapterTimeFormat.exact(420L) shouldBe "0:00:00.42"
            ChapterTimeFormat.exact(20L) shouldBe "0:00:00.02"
        }

        test("a drift offset is signed and read in minutes") {
            ChapterTimeFormat.offset(3_200L) shouldBe "+0:03.2"
            ChapterTimeFormat.offset(47_900L) shouldBe "+0:47.9"
            ChapterTimeFormat.offset(125_500L) shouldBe "+2:05.5"
        }

        test("a negative offset uses a true minus, not a hyphen") {
            // These sit beside a "+" in the anchor cards, and a hyphen is visibly shorter.
            withClue("U+2212 MINUS SIGN") {
                ChapterTimeFormat.offset(-3_200L) shouldBe "−0:03.2"
            }
        }

        // Typing a start: the row shows `h:mm:ss.t`, so the field must take back exactly that, and
        // the shorter shapes people actually type. Anything else is refused rather than guessed at.
        test("a typed time reads back exactly what the row displays") {
            ChapterTimeFormat.parsePrecise(ChapterTimeFormat.precise(position)) shouldBe 148_328_400L
            ChapterTimeFormat.parsePrecise(ChapterTimeFormat.exact(position)) shouldBe 148_328_420L
        }

        test("milliseconds can be typed in full") {
            ChapterTimeFormat.parsePrecise("41:12:08.423") shouldBe 148_328_423L
        }

        test("shorter shapes mean what they look like") {
            withClue("m:ss") { ChapterTimeFormat.parsePrecise("3:05") shouldBe 185_000L }
            withClue("seconds alone") { ChapterTimeFormat.parsePrecise("75.5") shouldBe 75_500L }
            withClue("no hours but a fraction") { ChapterTimeFormat.parsePrecise("12:08.4") shouldBe 728_400L }
        }

        test("surrounding spaces are forgiven; hours may run past 24") {
            ChapterTimeFormat.parsePrecise("  65:00:00 ") shouldBe 234_000_000L
        }

        test("anything that is not a time is refused rather than guessed at") {
            listOf("", "abc", "1:2:3:4", "1:60", "1:05:60", "-0:05", "1.2.3", "12:", ":30", "0:05.1234")
                .forEach { typed -> withClue("'$typed'") { ChapterTimeFormat.parsePrecise(typed) shouldBe null } }
        }

        test("zero offset reads as a plus, because no drift is not negative drift") {
            ChapterTimeFormat.offset(0L) shouldBe "+0:00.0"
        }
    })
