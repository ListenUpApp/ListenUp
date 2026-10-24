package com.calypsan.listenup.client.presentation.player

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PlayerProgressTest :
    FunSpec({
        fun at(
            position: Long,
            duration: Long,
        ) = PlayerProgress(0, 0, position, duration)

        test("the book percent is floored, so 99.9% reads 99 and only the end reads 100") {
            at(18_018_000, 163_800_000).bookPercent shouldBe 11
            at(163_636_200, 163_800_000).bookPercent shouldBe 99
            at(163_800_000, 163_800_000).bookPercent shouldBe 100
        }

        test("no duration is 0%, and a position past the end is still 100%") {
            at(5_000, 0).bookPercent shouldBe 0
            at(200_000_000, 163_800_000).bookPercent shouldBe 100
        }

        test("left at this speed is the book's remainder divided by the rate") {
            val p = PlayerProgress(0, 0, bookPositionMs = 0, bookDurationMs = 145_800_000) // 40h 30m
            p.bookRemainingAtSpeedMs(1.25f) shouldBe 116_640_000 // 32h 24m
            p.bookRemainingAtSpeedMs(0f) shouldBe 145_800_000
        }

        test("Zero is 0%") {
            PlayerProgress.Zero.bookPercent shouldBe 0
        }
    })
