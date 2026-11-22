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

        test("in a long book the credits are capped at a minute, so twelve minutes from the end is not finished") {
            val twentyHours = 20L * 60 * 60 * 1000
            // 99% of twenty hours leaves twelve minutes — the last chapter, not the credits.
            FinishedPolicy.isFinished(positionMs = twentyHours - 12 * 60_000L, durationMs = twentyHours, flag = false) shouldBe
                false
            FinishedPolicy.isFinished(positionMs = twentyHours - 45_000L, durationMs = twentyHours, flag = false) shouldBe true
        }

        test("in a short book the credits are the last one percent") {
            val tenMinutes = 10L * 60_000
            FinishedPolicy.isFinished(positionMs = tenMinutes - 5_000L, durationMs = tenMinutes, flag = false) shouldBe true
            FinishedPolicy.isFinished(positionMs = tenMinutes - 7_000L, durationMs = tenMinutes, flag = false) shouldBe false
        }

        test("an end-of-media signal is accepted only near the end") {
            FinishedPolicy.acceptsEndedSignal(positionMs = 91_000L, durationMs = 100_000L) shouldBe true
            FinishedPolicy.acceptsEndedSignal(positionMs = 50_000L, durationMs = 100_000L) shouldBe false
            FinishedPolicy.acceptsEndedSignal(positionMs = 50_000L, durationMs = 0L) shouldBe false
        }

        // #1742: a stream whose final, sub-second segment cannot be fetched has played everything a
        // listener can hear of the book. That is the end of it, not an error card a second from home.
        test("a stream that fails in the book's last second has reached its end") {
            FinishedPolicy.failureEndsBook(positionMs = 91_161_054L, durationMs = 91_161_455L) shouldBe true
            FinishedPolicy.failureEndsBook(positionMs = 99_000L, durationMs = 100_000L) shouldBe true
        }

        test("a stream that fails with more than a second still to play has not") {
            FinishedPolicy.failureEndsBook(positionMs = 98_999L, durationMs = 100_000L) shouldBe false
            FinishedPolicy.failureEndsBook(positionMs = 0L, durationMs = 0L) shouldBe false
            // A one-second book cannot be finished by failing at its start.
            FinishedPolicy.failureEndsBook(positionMs = 0L, durationMs = 900L) shouldBe false
        }
    })
