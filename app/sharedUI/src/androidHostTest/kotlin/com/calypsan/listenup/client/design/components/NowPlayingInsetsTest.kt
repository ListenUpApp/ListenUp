package com.calypsan.listenup.client.design.components

import androidx.compose.ui.unit.dp
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class NowPlayingInsetsTest :
    FunSpec({
        test("nowPlayingClearance returns footprint when bar visible") {
            nowPlayingClearance(barVisible = true, latchedFootprint = 96.dp) shouldBe 96.dp
        }
        test("nowPlayingClearance returns zero when bar hidden") {
            nowPlayingClearance(barVisible = false, latchedFootprint = 96.dp) shouldBe 0.dp
        }
        test("latchFootprint adopts a new positive measurement while visible") {
            latchFootprint(current = 0.dp, measured = 104.dp, barVisible = true) shouldBe 104.dp
        }
        test("latchFootprint keeps last value when bar hidden") {
            latchFootprint(current = 104.dp, measured = 0.dp, barVisible = false) shouldBe 104.dp
        }
        test("latchFootprint ignores non-positive measurements while visible") {
            latchFootprint(current = 104.dp, measured = 0.dp, barVisible = true) shouldBe 104.dp
        }
    })
