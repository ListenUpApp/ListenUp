package com.calypsan.listenup.client.features.nowplaying

import com.calypsan.listenup.client.features.nowplaying.components.morphCheckedFor
import com.calypsan.listenup.client.presentation.player.PrimaryControl
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Buffering keeps the button's last settled shape: a stall from playing stays square, from paused stays round. */
class PlayMorphShapeTest :
    FunSpec({
        test("busy keeps the last settled shape") {
            morphCheckedFor(primary = PrimaryControl.Busy, lastChecked = true) shouldBe true
            morphCheckedFor(primary = PrimaryControl.Busy, lastChecked = false) shouldBe false
        }

        test("play and pause settle the shape") {
            morphCheckedFor(primary = PrimaryControl.Pause, lastChecked = false) shouldBe true
            morphCheckedFor(primary = PrimaryControl.Play, lastChecked = true) shouldBe false
        }
    })
