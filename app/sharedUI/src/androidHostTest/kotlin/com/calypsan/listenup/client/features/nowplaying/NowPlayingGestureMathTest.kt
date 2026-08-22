package com.calypsan.listenup.client.features.nowplaying

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class NowPlayingGestureMathTest :
    FunSpec({

        test("a slow but clear upward drag expands") {
            NowPlayingGestureMath.shouldExpand(travelDp = -60f, velocityDpPerSec = -10f) shouldBe true
        }

        test("a fast upward flick expands even though it barely moved") {
            NowPlayingGestureMath.shouldExpand(travelDp = -8f, velocityDpPerSec = -1500f) shouldBe true
        }

        test("a small slow upward nudge does not expand") {
            NowPlayingGestureMath.shouldExpand(travelDp = -12f, velocityDpPerSec = -100f) shouldBe false
        }

        test("a downward drag never expands, however far or fast") {
            NowPlayingGestureMath.shouldExpand(travelDp = 300f, velocityDpPerSec = 4000f) shouldBe false
        }

        test("a resting finger does not expand") {
            NowPlayingGestureMath.shouldExpand(travelDp = 0f, velocityDpPerSec = 0f) shouldBe false
        }

        test("the travel threshold commits exactly past it, not at it") {
            NowPlayingGestureMath.shouldExpand(NowPlayingGestureMath.EXPAND_TRAVEL_DP, 0f) shouldBe false
            NowPlayingGestureMath.shouldExpand(NowPlayingGestureMath.EXPAND_TRAVEL_DP - 0.1f, 0f) shouldBe true
        }
    })
