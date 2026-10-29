package com.calypsan.listenup.client.features.nowplaying.morph

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import com.calypsan.listenup.client.features.nowplaying.EntryMotion
import com.calypsan.listenup.client.features.nowplaying.NO_COVER
import com.calypsan.listenup.client.features.nowplaying.NowPlayingLayout
import com.calypsan.listenup.client.features.nowplaying.loadedPlayerState
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Canvas `Edges`: wide windows, the toolbar and closes that navigate fade through; nothing flies, nothing drags. */
@RunWith(RobolectricTestRunner::class)
class FadeThroughTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val rig = MorphRig(composeRule)

    private fun openAndWatch() {
        composeRule.mainClock.autoAdvance = false
        rig.sheet.expand()
        repeat(6) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.onNodeWithTag(MORPH_COVER_TAG, useUnmergedTree = true).assertDoesNotExist()
        }
        // The fade's springs are still running a few frames in, and the morph does not move.
        rig.visibility.isIdle shouldBe false
        rig.morph.isInFlight shouldBe false
        composeRule.mainClock.advanceTimeBy(1_000)
        rig.visibility.currentState shouldBe true
        composeRule.mainClock.autoAdvance = true
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `beside the rail the player fades through, and a drag down does not close it`() {
        rig.usesPhoneBar = false
        rig.layout = NowPlayingLayout.SideBySide
        rig.show(loadedPlayerState(coverPath = NO_COVER))
        rig.entryMotion shouldBe EntryMotion.FadeThrough
        openAndWatch()
        composeRule.onNodeWithContentDescription("Collapse player").performTouchInput {
            swipeDown(startY = center.y, endY = center.y + 600f, durationMillis = 800)
        }
        composeRule.waitForIdle()
        rig.sheet.expanded.value shouldBe true
    }

    @Test
    @Config(qualifiers = "w700dp-h1000dp")
    fun `the toolbar over a stacked player fades through`() {
        rig.usesPhoneBar = false
        rig.layout = NowPlayingLayout.Stacked
        rig.show(loadedPlayerState(coverPath = NO_COVER))
        rig.entryMotion shouldBe EntryMotion.FadeThrough
        openAndWatch()
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp")
    fun `Go to series from the shell closes by fading through, since the bar is about to move`() {
        rig.show(loadedPlayerState(coverPath = NO_COVER))
        rig.sheet.expand()
        composeRule.waitForIdle()
        rig.entryMotion shouldBe EntryMotion.Morph
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnIdle {
            rig.sheet.collapse()
            rig.home = BarHome.Detail
        }
        repeat(4) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.onNodeWithTag(MORPH_COVER_TAG, useUnmergedTree = true).assertDoesNotExist()
        }
        rig.entryMotion shouldBe EntryMotion.FadeThrough
        rig.morph.progress shouldBe 0f
        composeRule.mainClock.advanceTimeBy(1_000)
        rig.visibility.currentState shouldBe false
        // Once it has gone, the bar morphs again.
        rig.entryMotion shouldBe EntryMotion.Morph
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp")
    fun `coming back from the reader, Now Playing fades through`() {
        rig.home = BarHome.Reader
        rig.show(loadedPlayerState(coverPath = NO_COVER))
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnIdle {
            rig.home = BarHome.Shell
            rig.sheet.expand()
        }
        repeat(4) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.onNodeWithTag(MORPH_COVER_TAG, useUnmergedTree = true).assertDoesNotExist()
        }
        rig.entryMotion shouldBe EntryMotion.FadeThrough
    }
}
