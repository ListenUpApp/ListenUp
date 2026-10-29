package com.calypsan.listenup.client.design.components

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe

/**
 * Pins where the scrubber's parts sit, as M3's `Slider` lays them out: the track inset half the handle, the handle's
 * centre along that inset track, and the wave and the inactive track stopping a handle-half plus the gap short of it.
 * The slider and the morph's flying line both draw from this, so the line lands where the slider is.
 */
class ScrubberTrackGeometryTest :
    FunSpec({
        // A 404 px slider with a 4 px handle and 6 px gaps: a 400 px track from 2 to 402.
        val width = 404f
        val handle = 4f
        val gap = 6f

        test("left to right, the handle sits along the inset track and the gaps clear it on both sides") {
            val parts = scrubberTrackGeometry(width = width, fraction = 0.25f, handleWidth = handle, handleGap = gap)
            parts.trackStart shouldBe 2f
            parts.trackEnd shouldBe 402f
            parts.handleCentre shouldBe 102f
            parts.waveEnd shouldBe 94f
            parts.inactiveStart shouldBe 110f
        }

        test("right to left, every part mirrors about the slider's middle") {
            val parts =
                scrubberTrackGeometry(width = width, fraction = 0.25f, handleWidth = handle, handleGap = gap, isRtl = true)
            parts.trackStart shouldBe 402f
            parts.trackEnd shouldBe 2f
            parts.handleCentre shouldBe 302f
            parts.waveEnd shouldBe 310f
            parts.inactiveStart shouldBe 294f
        }

        test("with no handle shown there is no inset and no gap: the bar's plain line") {
            val parts =
                scrubberTrackGeometry(
                    width = width,
                    fraction = 0.5f,
                    handleWidth = handle,
                    handleGap = gap,
                    handleShown = 0f,
                )
            parts.trackStart shouldBe 0f
            parts.trackEnd shouldBe width
            parts.handleCentre shouldBe 202f
            parts.waveEnd shouldBe 202f
            parts.inactiveStart shouldBe 202f
        }

        test("a half-shown handle eases the inset and the gaps in halfway") {
            val parts =
                scrubberTrackGeometry(
                    width = width,
                    fraction = 0f,
                    handleWidth = handle,
                    handleGap = gap,
                    handleShown = 0.5f,
                )
            parts.trackStart shouldBe 1f
            parts.handleCentre shouldBe 1f
            parts.inactiveStart shouldBe (5f plusOrMinus 1e-4f)
        }

        test("offsetting moves every part alike") {
            val parts = scrubberTrackGeometry(width = width, fraction = 1f, handleWidth = handle, handleGap = gap)
            parts.offsetBy(10f) shouldBe
                ScrubberTrackGeometry(
                    trackStart = 12f,
                    waveEnd = 404f,
                    handleCentre = 412f,
                    inactiveStart = 420f,
                    trackEnd = 412f,
                )
        }
    })
