package com.calypsan.listenup.client.features.nowplaying.morph

import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.calypsan.listenup.client.features.nowplaying.NO_COVER
import com.calypsan.listenup.client.features.nowplaying.loadedPlayerState
import com.calypsan.listenup.client.presentation.player.BackPreviewWeight
import io.kotest.matchers.floats.plusOrMinus
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import com.calypsan.listenup.client.features.nowplaying.components.PLAYER_COVER_TAG
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.assertions.withClue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Canvas `GestureAndroid`, BACK: Material's preview while the gesture runs, then the morph from the shrunken player. */
@Config(qualifiers = "w412dp-h915dp")
@RunWith(RobolectricTestRunner::class)
class PredictiveBackMorphTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val rig = MorphRig(composeRule)
    private val dispatcher: OnBackPressedDispatcher get() = composeRule.activity.onBackPressedDispatcher

    private fun event(progress: Float) = BackEventCompat(10f, 400f, progress, BackEventCompat.EDGE_LEFT)

    private fun openWithBack() {
        rig.show(loadedPlayerState(coverPath = NO_COVER))
        rig.sheet.expand()
        composeRule.waitForIdle()
        dispatcher.hasEnabledCallbacks() shouldBe true
        composeRule.runOnIdle {
            dispatcher.dispatchOnBackStarted(event(0f))
            dispatcher.dispatchOnBackProgressed(event(0.6f))
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `mid-gesture the preview shrinks the player and the morph has not moved`() {
        openWithBack()
        rig.morph.progress shouldBe 1f
        rig.morph.backPreview shouldBe BackPreviewWeight.None
        rig.sheet.expanded.value shouldBe true
    }

    @Test
    fun `on commit the morph runs from the shrunken player into the bar`() {
        openWithBack()
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnIdle { dispatcher.onBackPressed() }
        composeRule.mainClock.advanceTimeBy(80)
        rig.morph.progress shouldBeLessThan 1f
        rig.morph.progress shouldBeGreaterThan 0f
        // Material's preview at 0.6: 1 − 0.6 × 0.1.
        rig.morph.backPreview.scale shouldBe (0.94f plusOrMinus 1e-4f)
        rig.morph.backPreview
            .at(0.5f)
            .scale shouldBe (0.97f plusOrMinus 1e-4f)
        composeRule.mainClock.advanceTimeBy(1_500)
        rig.morph.progress shouldBe 0f
        rig.sheet.expanded.value shouldBe false
        rig.morph.backPreview shouldBe BackPreviewWeight.None
    }

    @Test
    fun `on commit the flying parts start from the shrunken page, so nothing jumps`() {
        openWithBack()
        val shrunken = bounds(PLAYER_COVER_TAG)
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnIdle { dispatcher.onBackPressed() }
        // The commit frame: the first in which the parts fly.
        var frames = 0
        while (!rig.morph.isMorphing && frames < 6) {
            composeRule.mainClock.advanceTimeByFrame()
            frames++
        }
        rig.morph.isMorphing shouldBe true
        rig.morph.progress shouldBeGreaterThan 0.97f
        val flying = bounds(MORPH_COVER_TAG)
        // Within a few pixels of the shrunken cover: at most a frame along the arc, never the 10–25px jump.
        withClue("flying $flying, shrunken $shrunken, progress ${rig.morph.progress}") {
            flying.zip(shrunken).forEach { (a, b) -> a shouldBe (b plusOrMinus 3f) }
        }
    }

    @Test
    fun `on cancel it springs back open, and the next gesture still works`() {
        rig.show(loadedPlayerState(coverPath = NO_COVER))
        rig.sheet.expand()
        composeRule.waitForIdle()
        val atRest = bounds(PLAYER_COVER_TAG)
        composeRule.runOnIdle {
            dispatcher.dispatchOnBackStarted(event(0f))
            dispatcher.dispatchOnBackProgressed(event(0.6f))
        }
        composeRule.waitForIdle()
        // The preview really shrank the sheet, so the check below can fail.
        (bounds(PLAYER_COVER_TAG)[2] - bounds(PLAYER_COVER_TAG)[0]) shouldBeLessThan (atRest[2] - atRest[0]) - 2f
        composeRule.runOnIdle { dispatcher.dispatchOnBackCancelled() }
        composeRule.waitForIdle()
        rig.morph.backPreview shouldBe BackPreviewWeight.None
        bounds(PLAYER_COVER_TAG).zip(atRest).forEach { (a, b) -> a shouldBe (b plusOrMinus 0.5f) }
        rig.morph.progress shouldBe 1f
        rig.sheet.expanded.value shouldBe true
        composeRule.runOnIdle {
            dispatcher.dispatchOnBackStarted(event(0f))
            dispatcher.onBackPressed()
        }
        composeRule.waitForIdle()
        rig.sheet.expanded.value shouldBe false
    }

    private fun bounds(tag: String): List<Float> {
        val r = composeRule.onNodeWithTag(tag, useUnmergedTree = true).getBoundsInRoot()
        return listOf(r.left.value, r.top.value, r.right.value, r.bottom.value)
    }
}
