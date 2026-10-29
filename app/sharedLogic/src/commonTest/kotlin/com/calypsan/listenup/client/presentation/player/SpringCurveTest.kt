package com.calypsan.listenup.client.presentation.player

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe

class SpringCurveTest :
    FunSpec({
        val settle = PlayerMorph.SETTLE_SPRING

        test("the default spatial spring settles in about half a second (the canvas's ~500 ms)") {
            SpringCurve.settleMillis(settle) shouldBeInRange 400..520
        }

        test("it starts at 0, ends at exactly 1, and overshoots a little (damping 0.8 ≈ 1.5 %)") {
            val samples = SpringCurve.sample(settle, initialVelocity = 0f, stepMs = 16)
            samples.first() shouldBe 0f
            samples.last() shouldBe 1f
            samples.max() shouldBe (1.015f plusOrMinus 0.005f)
        }

        test("the slow spatial spring is slower") {
            SpringCurve.settleMillis(PlayerMorph.TAP_SPRING) shouldBeGreaterThan SpringCurve.settleMillis(settle)
        }

        test("a carried velocity (progress/s) steepens the start") {
            SpringCurve.sample(settle, initialVelocity = 3f, stepMs = 16)[1] shouldBeGreaterThan
                SpringCurve.sample(settle, 0f, 16)[1]
        }
    })
