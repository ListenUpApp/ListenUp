package com.calypsan.listenup.client.design.components

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Where a touch lands on five stars, in half stars. */
class RatingStarsGeometryTest :
    FunSpec({
        val width = 200f // five 40px stars

        test("the right half of a star counts it whole") {
            halfStarsAt(x = 150f, width = width) shouldBe 8
        }

        test("the left half of a star counts half of it") {
            halfStarsAt(x = 125f, width = width) shouldBe 7
        }

        test("anything left of the first star still sets one star") {
            halfStarsAt(x = 0f, width = width) shouldBe 2
            halfStarsAt(x = -30f, width = width) shouldBe 2
        }

        test("anything past the last star is five stars") {
            halfStarsAt(x = 260f, width = width) shouldBe 10
        }
    })
