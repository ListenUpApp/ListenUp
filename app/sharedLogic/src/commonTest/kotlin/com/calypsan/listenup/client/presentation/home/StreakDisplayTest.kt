package com.calypsan.listenup.client.presentation.home

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Spec §2.6: a live streak is a chip plus the best; a lapsed one is the best alone; none is nothing — never "0-day". */
class StreakDisplayTest :
    FunSpec({
        test("a live streak shows the chip and the best") {
            streakDisplay(current = 5, best = 12) shouldBe StreakDisplay(chipDays = 5, bestDays = 12)
        }

        test("a lapsed streak shows only the best") {
            streakDisplay(current = 0, best = 12) shouldBe StreakDisplay(chipDays = null, bestDays = 12)
        }

        test("no streak ever shows nothing") {
            streakDisplay(current = 0, best = 0) shouldBe StreakDisplay(chipDays = null, bestDays = null)
        }

        test("the best is never below the live streak") {
            streakDisplay(current = 3, best = 0) shouldBe StreakDisplay(chipDays = 3, bestDays = 3)
        }
    })
