package com.calypsan.listenup.client.features.nowplaying.morph

import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.calypsan.listenup.client.features.nowplaying.NO_COVER
import com.calypsan.listenup.client.features.nowplaying.loadedPlayerState
import com.calypsan.listenup.client.presentation.player.BackPreviewWeight
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe
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
    fun `on cancel it springs back open, and the next gesture still works`() {
        openWithBack()
        composeRule.runOnIdle { dispatcher.dispatchOnBackCancelled() }
        composeRule.waitForIdle()
        rig.morph.progress shouldBe 1f
        rig.sheet.expanded.value shouldBe true
        composeRule.runOnIdle {
            dispatcher.dispatchOnBackStarted(event(0f))
            dispatcher.onBackPressed()
        }
        composeRule.waitForIdle()
        rig.sheet.expanded.value shouldBe false
    }
}
