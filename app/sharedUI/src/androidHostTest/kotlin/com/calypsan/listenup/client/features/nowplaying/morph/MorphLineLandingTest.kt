package com.calypsan.listenup.client.features.nowplaying.morph

import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.Density
import com.calypsan.listenup.client.design.components.ScrubberHandleGap
import com.calypsan.listenup.client.design.components.ScrubberHandleWidth
import com.calypsan.listenup.client.design.components.scrubberTrackGeometry
import com.calypsan.listenup.client.features.nowplaying.NO_COVER
import com.calypsan.listenup.client.features.nowplaying.loadedPlayerState
import com.calypsan.listenup.client.features.nowplaying.samplePlayerProgress
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.Rule
import kotlin.math.roundToInt
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The progress line lands on Now Playing's scrubber: at the end of the flight it stands where the slider's wave,
 * handle and inactive track stand, and it waves on the player's one shared phase, so nothing jumps as it hands over.
 */
@Config(qualifiers = "w412dp-h915dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class MorphLineLandingTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val rig = MorphRig(composeRule)

    private fun at(p: Float) {
        rig.morph.snapDrag(p)
        composeRule.waitForIdle()
        // A second pass: the page's anchors land after the frame that composed it.
        rig.morph.snapDrag(p + 1e-3f)
        composeRule.waitForIdle()
        rig.morph.snapDrag(p)
        composeRule.waitForIdle()
    }

    @Test
    fun `at Now Playing the line's wave end, handle and inactive start are the slider's`() {
        rig.show(loadedPlayerState(coverPath = NO_COVER))
        at(1f)
        // The slider's own layout bounds: its semantics bounds are widened to the touch target.
        val slider =
            composeRule
                .onNodeWithContentDescription("Chapter position")
                .fetchSemanticsNode()
                .layoutInfo.coordinates
                .boundsInRoot()
        val density = Density(rig.density)
        val left = slider.left
        val width = slider.width
        val expected =
            with(density) {
                scrubberTrackGeometry(
                    width = width,
                    fraction = samplePlayerProgress.chapterFraction,
                    handleWidth = ScrubberHandleWidth.toPx(),
                    handleGap = ScrubberHandleGap.toPx(),
                ).offsetBy(left)
            }

        val landed =
            rig.morph
                .lineFlightParts(fraction = samplePlayerProgress.chapterFraction, isRtl = false, density = density)
                .shouldNotBeNull()
                .geometry

        landed.trackStart shouldBe (expected.trackStart plusOrMinus 1f)
        landed.waveEnd shouldBe (expected.waveEnd plusOrMinus 1f)
        landed.handleCentre shouldBe (expected.handleCentre plusOrMinus 1f)
        landed.inactiveStart shouldBe (expected.inactiveStart plusOrMinus 1f)
        landed.trackEnd shouldBe (expected.trackEnd plusOrMinus 1f)
    }

    @Test
    fun `the flying line waves on the player's shared phase`() {
        val travel = mutableFloatStateOf(0f)
        rig.show(loadedPlayerState(coverPath = NO_COVER))
        rig.morph.wavePhase.loop = travel
        at(MID_FLIGHT)
        val line =
            rig.morph
                .lineFlightParts(samplePlayerProgress.chapterFraction, isRtl = false, density = Density(rig.density))
                .shouldNotBeNull()

        // Look only at the flying line's own band, mid-flight, clear of the bar's line and the scrubber: moving the
        // shared phase half a wavelength must move what the flight draws there.
        fun band(): List<Color> {
            val pixels = composeRule.onRoot().captureToImage().toPixelMap()
            val xs = line.geometry.trackStart.roundToInt()..line.geometry.waveEnd.roundToInt()
            val ys = (line.centreY - BAND_PX).roundToInt()..(line.centreY + BAND_PX).roundToInt()
            return xs.flatMap { x -> ys.map { y -> pixels[x, y] } }
        }
        val before = band()
        travel.floatValue = HALF_WAVELENGTH
        composeRule.waitForIdle()

        (band() != before) shouldBe true
    }

    private companion object {
        const val HALF_WAVELENGTH = 0.5f
        const val MID_FLIGHT = 0.5f

        /** The wave's reach either side of its centre line (3dp crest plus half the 4dp stroke), at density 1. */
        const val BAND_PX = 5f
    }
}
