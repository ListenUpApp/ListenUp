package com.calypsan.listenup.client.features.nowplaying.morph

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SpringSpec
import com.calypsan.listenup.client.presentation.player.SpringParameters
import androidx.compose.material3.MotionScheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.swipeWithVelocity
import com.calypsan.listenup.client.design.haptics.Haptics
import com.calypsan.listenup.client.features.nowplaying.MINI_BODY_TAG
import com.calypsan.listenup.client.features.nowplaying.NO_COVER
import com.calypsan.listenup.client.features.nowplaying.loadedPlayerState
import com.calypsan.listenup.client.presentation.player.MorphEnd
import com.calypsan.listenup.client.presentation.player.PlayerMorph
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Canvas `GestureAndroid`: anywhere on the bar, buttons included, a drag over 8 dp owns the morph; under it, a tap. */
@Config(qualifiers = "w412dp-h915dp")
@RunWith(RobolectricTestRunner::class)
class BarDragTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val rig = MorphRig(composeRule)
    private val haptics = CountingHaptics()

    private fun show() = rig.show(loadedPlayerState(coverPath = NO_COVER), haptics = haptics)

    private val barTop: Float get() =
        rig.morph.anchors.barBounds
            .shouldNotBeNull()
            .top

    private fun dp(value: Float) = value * rig.density

    @Test
    fun `a finger 300 dp up holds progress there, mid-drag`() {
        show()
        composeRule.onNodeWithTag(MINI_BODY_TAG).performTouchInput {
            down(center)
            moveBy(Offset(0f, -dp(150f)))
            moveBy(Offset(0f, -dp(150f)))
        }
        composeRule.waitForIdle()
        // The first 8 dp are the slop, which the drag does not count.
        rig.morph.progress shouldBe ((dp(300f) - dp(PlayerMorph.TOUCH_SLOP_DP)) / barTop plusOrMinus 0.02f)
        rig.morph.isDragging shouldBe true
    }

    @Test
    fun `released slowly past halfway, it opens`() {
        show()
        composeRule.onNodeWithTag(MINI_BODY_TAG).performTouchInput {
            swipeUp(startY = center.y, endY = center.y - 0.6f * barTop, durationMillis = 1_500)
        }
        composeRule.waitForIdle()
        rig.sheet.expanded.value shouldBe true
        composeRule.onNodeWithContentDescription("Collapse player").assertExists()
    }

    @Test
    fun `released slowly short of halfway, it springs back`() {
        show()
        composeRule.onNodeWithTag(MINI_BODY_TAG).performTouchInput {
            swipeUp(startY = center.y, endY = center.y - 0.4f * barTop, durationMillis = 1_500)
        }
        composeRule.waitForIdle()
        rig.sheet.expanded.value shouldBe false
        rig.morph.progress shouldBe 0f
    }

    @Test
    fun `a fast short flick opens`() {
        show()
        composeRule.onNodeWithTag(MINI_BODY_TAG).performTouchInput {
            swipeWithVelocity(start = center, end = center - Offset(0f, dp(40f)), endVelocity = dp(1_500f))
        }
        composeRule.waitForIdle()
        rig.sheet.expanded.value shouldBe true
    }

    @Test
    fun `up to 70 percent and back to 30 before letting go springs back`() {
        show()
        composeRule.onNodeWithTag(MINI_BODY_TAG).performTouchInput {
            down(center)
            repeat(14) { moveBy(Offset(0f, -0.05f * barTop - dp(1f)), delayMillis = 50) }
            repeat(8) { moveBy(Offset(0f, 0.05f * barTop), delayMillis = 50) }
            repeat(10) { moveBy(Offset.Zero, delayMillis = 50) }
            up()
        }
        composeRule.waitForIdle()
        rig.sheet.expanded.value shouldBe false
        rig.morph.progress shouldBe 0f
    }

    @Test
    fun `a press that moves 6 dp on Pause is a tap`() {
        show()
        composeRule.onNodeWithContentDescription("Pause").performTouchInput {
            down(center)
            moveBy(Offset(0f, -dp(6f)))
            up()
        }
        composeRule.waitForIdle()
        rig.primaryPresses shouldBe 1
        rig.sheet.expanded.value shouldBe false
    }

    @Test
    fun `a drag that starts on Pause cancels the press and moves the morph`() {
        show()
        composeRule.onNodeWithContentDescription("Pause").performTouchInput {
            down(center)
            moveBy(Offset(0f, -dp(120f)))
        }
        composeRule.waitForIdle()
        rig.morph.progress shouldBeGreaterThan 0f
        // Now Playing has composed under the finger with its own Pause; the bar's is the first.
        composeRule.onAllNodesWithContentDescription("Pause")[0].performTouchInput { up() }
        composeRule.waitForIdle()
        rig.primaryPresses shouldBe 0
    }

    @Test
    fun `the tap opens on the same frames, on the slow spatial spring`() {
        show()
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag(MINI_BODY_TAG).performClick()
        composeRule.mainClock.advanceTimeBy(100)
        rig.morph.progress shouldBeGreaterThan 0f
        rig.morph.progress shouldBeLessThan 1f
        composeRule.mainClock.advanceTimeBy(2_000)
        rig.morph.progress shouldBe 1f
    }

    @Test
    fun `each crossing of halfway ticks once`() {
        show()
        composeRule.onNodeWithTag(MINI_BODY_TAG).performTouchInput {
            down(center)
            moveBy(Offset(0f, -0.7f * barTop))
            moveBy(Offset(0f, 0.5f * barTop))
            repeat(10) { moveBy(Offset.Zero, delayMillis = 50) }
            up()
        }
        composeRule.waitForIdle()
        haptics.thresholds shouldBe 2
    }

    @Test
    fun `TalkBack opens with the body's click and never drags`() {
        show()
        composeRule.onNodeWithTag(MINI_BODY_TAG).performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        rig.sheet.expanded.value shouldBe true
        rig.tapOpens shouldBe 1
    }

    @Test
    fun `a fast release carries its speed into the spring`() {
        show()
        composeRule.mainClock.autoAdvance = false
        fun progressAfterRelease(velocityDpPerSec: Float): Float {
            rig.morph.snapTo(MorphEnd.Bar)
            rig.morph.snapDrag(0.6f)
            rig.morph.releaseDrag(velocityDpPerSec, rig.density)
            repeat(3) { composeRule.mainClock.advanceTimeByFrame() }
            return rig.morph.progress
        }
        val still = progressAfterRelease(0f)
        val flung = progressAfterRelease(-2_000f)
        // The same start and end; only the carried velocity differs, so the flung release is further along.
        flung shouldBeGreaterThan still + 0.02f
    }

    @Test
    fun `the springs are M3 Expressive's, the same numbers as the shared ones`() {
        val scheme = MotionScheme.expressive()
        expressiveSpring(scheme.defaultSpatialSpec()) shouldBe PlayerMorph.SETTLE_SPRING
        expressiveSpring(scheme.slowSpatialSpec()) shouldBe PlayerMorph.TAP_SPRING
    }

    private fun expressiveSpring(spec: FiniteAnimationSpec<Float>): SpringParameters {
        val spring = spec as SpringSpec<Float>
        return SpringParameters(stiffness = spring.stiffness, dampingRatio = spring.dampingRatio)
    }

    private class CountingHaptics : Haptics {
        var thresholds = 0

        override fun selectionTick() = Unit

        override fun press() = Unit

        override fun toggle(on: Boolean) = Unit

        override fun longPress() = Unit

        override fun thresholdActivate() {
            thresholds++
        }

        override fun commit() = Unit
    }
}
