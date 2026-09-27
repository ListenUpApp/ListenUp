package com.calypsan.listenup.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FinishedPolicyTest :
    FunSpec({
        test("the finished flag alone counts, regardless of position") {
            FinishedPolicy.isFinished(positionMs = 0L, durationMs = 100_000L, flag = true) shouldBe true
        }

        test("stopping in the end credits counts as finished without the flag") {
            FinishedPolicy.isFinished(positionMs = 99_500L, durationMs = 100_000L, flag = false) shouldBe true
        }

        test("stopping short of the completion fraction does not count") {
            FinishedPolicy.isFinished(positionMs = 98_000L, durationMs = 100_000L, flag = false) shouldBe false
        }

        test("an unknown duration never counts as finished without the flag") {
            FinishedPolicy.isFinished(positionMs = 5_000L, durationMs = 0L, flag = false) shouldBe false
        }

        test("a progress fraction follows the same rule") {
            FinishedPolicy.isFinished(progressFraction = 0.995, flag = false) shouldBe true
            FinishedPolicy.isFinished(progressFraction = 0.5, flag = false) shouldBe false
        }

        test("an end-of-media signal is accepted only near the end") {
            FinishedPolicy.acceptsEndedSignal(positionMs = 91_000L, durationMs = 100_000L) shouldBe true
            FinishedPolicy.acceptsEndedSignal(positionMs = 50_000L, durationMs = 100_000L) shouldBe false
            FinishedPolicy.acceptsEndedSignal(positionMs = 50_000L, durationMs = 0L) shouldBe false
        }
    })
