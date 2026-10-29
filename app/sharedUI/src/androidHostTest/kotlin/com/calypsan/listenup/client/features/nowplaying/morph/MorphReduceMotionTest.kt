package com.calypsan.listenup.client.features.nowplaying.morph

import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.features.nowplaying.MINI_BODY_TAG
import com.calypsan.listenup.client.features.nowplaying.NO_COVER
import com.calypsan.listenup.client.features.nowplaying.loadedPlayerState
import com.calypsan.listenup.client.presentation.player.MorphElement
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Canvas `Reduce`: with animations removed nothing travels; the drag scrubs a crossfade, and a tap snaps. */
@Config(qualifiers = "w412dp-h915dp")
@RunWith(RobolectricTestRunner::class)
class MorphReduceMotionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val rig = MorphRig(composeRule)

    @Test
    fun `a drag scrubs the crossfade, with nothing flying and the sheet at rest`() {
        rig.show(loadedPlayerState(coverPath = NO_COVER), reduceMotion = true)
        rig.morph.snapDrag(0.4f)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(MORPH_COVER_TAG, useUnmergedTree = true).assertDoesNotExist()
        val sheet = GraphicsLayerScope().apply { sheetLayer(rig.morph) }
        sheet.alpha shouldBe (0.4f plusOrMinus 1e-4f)
        sheet.translationY shouldBe 0f
        GraphicsLayerScope().apply { revealLayer(rig.morph, MorphElement.Header) }.alpha shouldBe 1f
        GraphicsLayerScope().apply { navigationSlideLayer(rig.morph) }.translationY shouldBe 0f
    }

    @Test
    fun `a tap opens in a frame`() {
        rig.show(loadedPlayerState(coverPath = NO_COVER), reduceMotion = true)
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag(MINI_BODY_TAG).performClick()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeByFrame()
        rig.morph.progress shouldBe 1f
    }
}
