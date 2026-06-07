package com.calypsan.listenup.client.features.nowplaying

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe

class PlaybackSpeedPresetsTest :
    FunSpec({
        test("format renders whole speeds with a trailing .0x") {
            PlaybackSpeedPresets.format(1.0f) shouldBe "1.0x"
            PlaybackSpeedPresets.format(2.0f) shouldBe "2.0x"
        }

        test("format trims trailing zeros on fractional speeds") {
            PlaybackSpeedPresets.format(1.25f) shouldBe "1.25x"
            PlaybackSpeedPresets.format(1.5f) shouldBe "1.5x"
            PlaybackSpeedPresets.format(0.75f) shouldBe "0.75x"
        }

        test("snap rounds to the nearest 0.05 increment") {
            PlaybackSpeedPresets.snap(1.23f) shouldBe (1.25f plusOrMinus 0.001f)
            PlaybackSpeedPresets.snap(1.01f) shouldBe (1.0f plusOrMinus 0.001f)
            PlaybackSpeedPresets.snap(2.97f) shouldBe (2.95f plusOrMinus 0.001f)
        }
    })
