package com.calypsan.listenup.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class RatingKeyStepTest :
    FunSpec({
        test("the arrows step one half-star at a time") {
            RatingKeyStep.Increase.applyTo(6) shouldBe 7
            RatingKeyStep.Decrease.applyTo(6) shouldBe 5
        }

        test("Home and End jump to one and five stars") {
            RatingKeyStep.Lowest.applyTo(7) shouldBe 2
            RatingKeyStep.Highest.applyTo(3) shouldBe 10
        }

        test("the first step on an unrated input lands on one star") {
            RatingKeyStep.Increase.applyTo(0) shouldBe 2
            RatingKeyStep.Decrease.applyTo(0) shouldBe 2
        }

        test("steps never leave one to five stars") {
            RatingKeyStep.Decrease.applyTo(2) shouldBe 2
            RatingKeyStep.Increase.applyTo(10) shouldBe 10
        }
    })
