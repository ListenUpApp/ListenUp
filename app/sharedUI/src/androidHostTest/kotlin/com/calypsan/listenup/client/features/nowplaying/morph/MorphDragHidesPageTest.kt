package com.calypsan.listenup.client.features.nowplaying.morph

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import com.calypsan.listenup.client.features.nowplaying.MINI_BODY_TAG
import com.calypsan.listenup.client.features.nowplaying.NO_COVER
import com.calypsan.listenup.client.features.nowplaying.components.PLAYER_COVER_TAG
import com.calypsan.listenup.client.features.nowplaying.loadedPlayerState
import com.calypsan.listenup.client.features.nowplaying.samplePlayerProgress
import io.kotest.assertions.withClue
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/**
 * While a finger drags the bar up, the parts in flight are the only copies drawn: Now Playing's own cover, title and
 * scrubber stay hidden inside the rising sheet until the morph lands, exactly as when a tap opens it.
 */
@Config(qualifiers = "w412dp-h915dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class MorphDragHidesPageTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val rig = MorphRig(composeRule)

    @Test
    fun `mid-drag, Now Playing's cover, title and scrubber are not drawn under the flying ones`() {
        rig.show(loadedPlayerState(coverPath = NO_COVER))
        val barTop =
            rig.morph.anchors.barBounds
                .shouldNotBeNull()
                .top
        composeRule.onNodeWithTag(MINI_BODY_TAG).performTouchInput {
            down(center)
            repeat(DRAG_STEPS) { moveBy(Offset(0f, -barTop * HELD_AT / DRAG_STEPS)) }
        }
        composeRule.waitForIdle()
        rig.morph.isDragging shouldBe true
        rig.morph.progress shouldBe (HELD_AT plusOrMinus 0.05f)

        val pixels = composeRule.onRoot().captureToImage().toPixelMap()
        val sheetTop =
            rig.morph.anchors.sheet
                .shouldNotBeNull()
                .boundsInRoot()
                .top
        val flying = flyingRects()
        val pageTitle =
            composeRule
                .onAllNodesWithText("The Way of Kings")
                .fetchSemanticsNodes()
                .map { it.layoutInfo.coordinates.boundsInRoot() }
                .maxBy { it.width }

        val parts =
            mapOf(
                "cover" to boundsOf(composeRule.onNodeWithTag(PLAYER_COVER_TAG)),
                "title" to pageTitle,
                "scrubber" to sliderBounds(),
            )
        parts.forEach { (name, page) ->
            withClue("$name at $page, sheet top $sheetTop, flying $flying") {
                val colours = pixels.sample(page, below = sheetTop, outside = flying)
                colours.size shouldBeGreaterThan MIN_SAMPLES
                // Hidden, the part leaves only the sheet's own surface: one colour.
                colours.toSet().size shouldBe 1
            }
        }
    }

    /** Where the flying parts are drawn: their nodes, and the line's band (its handle's height). */
    private fun flyingRects(): List<Rect> {
        val parts =
            listOf(MORPH_COVER_TAG, MORPH_TITLE_TAG, MORPH_PLAY_TAG).flatMap { tag ->
                composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().map {
                    it.layoutInfo.coordinates.boundsInRoot()
                }
            }
        val line =
            rig.morph
                .lineFlightParts(samplePlayerProgress.chapterFraction, isRtl = false, density = Density(rig.density))
                .shouldNotBeNull()
        val band = LINE_BAND_DP * rig.density
        return parts + Rect(0f, line.centreY - band, PIXELS_WIDE, line.centreY + band)
    }

    private fun sliderBounds(): Rect = boundsOf(composeRule.onNodeWithContentDescription("Chapter position"))

    private fun boundsOf(node: SemanticsNodeInteraction): Rect =
        node
            .fetchSemanticsNode()
            .layoutInfo.coordinates
            .boundsInRoot()

    /** The colours inside [area], below the sheet's top edge and outside every flying part. */
    private fun PixelMap.sample(
        area: Rect,
        below: Float,
        outside: List<Rect>,
    ): List<Color> {
        val top = maxOf(area.top, below + 1f).roundToInt()
        val xs = area.left.roundToInt().coerceAtLeast(0) until area.right.roundToInt().coerceAtMost(width)
        val ys = top.coerceAtLeast(0) until area.bottom.roundToInt().coerceAtMost(height)
        return xs.flatMap { x ->
            ys
                .filter { y -> outside.none { it.contains(Offset(x.toFloat(), y.toFloat())) } }
                .map { y -> this[x, y] }
        }
    }

    private companion object {
        const val DRAG_STEPS = 10

        // Far enough up that the rising sheet has brought the scrubber on screen.
        const val HELD_AT = 0.75f

        // Wider than any test window, so the line's band spans it.
        const val PIXELS_WIDE = 100_000f
        const val MIN_SAMPLES = 50
        const val LINE_BAND_DP = 24f
    }
}
