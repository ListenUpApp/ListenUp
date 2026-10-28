package com.calypsan.listenup.client.features.nowplaying.morph

import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.LayoutDirection
import com.calypsan.listenup.client.features.nowplaying.MINI_BODY_TAG
import com.calypsan.listenup.client.features.nowplaying.MINI_COVER_TAG
import com.calypsan.listenup.client.features.nowplaying.NO_COVER
import com.calypsan.listenup.client.features.nowplaying.loadedPlayerState
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.max
import kotlin.math.min

/**
 * The morph in a right-to-left layout (an Arabic or Hebrew locale): the bar's cover sits at the right, and the flying
 * cover travels between the two covers where they really are on screen; the drag opens by the same rule as in LTR.
 */
@Config(qualifiers = "w412dp-h915dp")
@RunWith(RobolectricTestRunner::class)
class MorphRtlTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val rig = MorphRig(composeRule)

    private fun show() = rig.show(loadedPlayerState(coverPath = NO_COVER), layoutDirection = LayoutDirection.Rtl)

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
    fun `halfway, the flying cover lies between the bar's cover and Now Playing's`() {
        show()
        val bar = composeRule.onNodeWithTag(MINI_COVER_TAG, useUnmergedTree = true).getBoundsInRoot()
        val barLeft = bar.left.value * rig.density
        val barRight = bar.right.value * rig.density
        at(0.5f)
        val page = rig.morph.pageTarget(MorphPart.Cover).shouldNotBeNull()
        val flying = composeRule.onNodeWithTag(MORPH_COVER_TAG, useUnmergedTree = true).getBoundsInRoot()
        val left = flying.left.value * rig.density
        val right = flying.right.value * rig.density

        left shouldBeGreaterThan min(barLeft, page.left) - 1f
        right shouldBeLessThan max(barRight, page.right) + 1f
    }

    @Test
    fun `released slowly past halfway, it opens, as in LTR`() {
        show()
        val barTop =
            rig.morph.anchors.barBounds
                .shouldNotBeNull()
                .top
        composeRule.onNodeWithTag(MINI_BODY_TAG).performTouchInput {
            swipeUp(startY = center.y, endY = center.y - 0.6f * barTop, durationMillis = 1_500)
        }
        composeRule.waitForIdle()
        rig.sheet.expanded.value shouldBe true
    }
}
