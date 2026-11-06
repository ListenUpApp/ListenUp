package com.calypsan.listenup.client.playback

import com.calypsan.listenup.domain.VolumeBoostLimits
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PlaybackPresetsTest :
    FunSpec({

        test("a speed is written with a decimal point, at most two decimals, and the multiplication sign") {
            PlaybackPresets.speedLabel(1.0f) shouldBe "1.0×"
            PlaybackPresets.speedLabel(1.25f) shouldBe "1.25×"
            PlaybackPresets.speedLabel(0.5f) shouldBe "0.5×"
            PlaybackPresets.speedLabel(1.5f) shouldBe "1.5×"
            PlaybackPresets.speedLabel(3.0f) shouldBe "3.0×"
        }

        test("a speed that went through storage as 1.2499999 still reads 1.25") {
            PlaybackPresets.speedLabel(1.2499999f) shouldBe "1.25×"
        }

        test("the spoken number has no sign") {
            PlaybackPresets.speedNumber(1.0f) shouldBe "1.0"
            PlaybackPresets.speedNumber(1.25f) shouldBe "1.25"
        }

        test("the speed ladder is the nine presets, slowest first") {
            PlaybackPresets.SPEEDS shouldBe listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)
        }

        test("an off-ladder speed joins the choices in order, so the picker always has a checked row") {
            PlaybackPresets.speedChoicesIncluding(1.1f) shouldBe
                listOf(0.5f, 0.75f, 1.0f, 1.1f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)
        }

        test("a speed that is a rung within tolerance leaves the ladder unchanged") {
            PlaybackPresets.speedChoicesIncluding(1.4999999f) shouldBe PlaybackPresets.SPEEDS
        }

        test("skip lists are per direction") {
            PlaybackPresets.SKIP_FORWARD_SECONDS shouldBe listOf(10, 15, 20, 30, 45, 60, 90, 120)
            PlaybackPresets.SKIP_BACKWARD_SECONDS shouldBe listOf(5, 10, 15, 20, 30, 45, 60)
        }

        test("an off-list skip joins its direction's choices in order") {
            PlaybackPresets.skipForwardChoicesIncluding(25) shouldBe listOf(10, 15, 20, 25, 30, 45, 60, 90, 120)
            PlaybackPresets.skipBackwardChoicesIncluding(7) shouldBe listOf(5, 7, 10, 15, 20, 30, 45, 60)
            PlaybackPresets.skipBackwardChoicesIncluding(30) shouldBe PlaybackPresets.SKIP_BACKWARD_SECONDS
        }

        test("the car cycle steps up the ladder and wraps from fastest to slowest") {
            PlaybackPresets.nextSpeedInCycle(3.0f) shouldBe 0.5f
            PlaybackPresets.nextSpeedInCycle(1.0f) shouldBe 1.25f
            PlaybackPresets.nextSpeedInCycle(1.1f) shouldBe 1.25f
        }

        test("boost choices are the contract's ladder") {
            PlaybackPresets.BOOST_DB shouldBe VolumeBoostLimits.PRESETS_DB
        }
    })
