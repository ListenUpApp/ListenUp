package com.calypsan.listenup.client.features.nowplaying.morph

import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.calypsan.listenup.client.features.nowplaying.MINI_BODY_TAG
import com.calypsan.listenup.client.features.nowplaying.NO_COVER
import com.calypsan.listenup.client.features.nowplaying.loadedPlayerState
import com.calypsan.listenup.client.playback.PlayerPhase
import com.calypsan.listenup.client.presentation.player.PrimaryControl
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Canvas `Edges`: never stranded. A keyboard open focuses Collapse and a close gives focus back to the bar. */
@Config(qualifiers = "w412dp-h915dp")
@RunWith(RobolectricTestRunner::class)
class MorphFocusTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val rig = MorphRig(composeRule)

    @Test
    fun `with a keyboard, opening focuses Collapse and closing returns focus to the bar`() {
        lateinit var inputMode: InputModeManager
        rig.show(loadedPlayerState(coverPath = NO_COVER), probeInputMode = { inputMode = LocalInputModeManager.current })
        composeRule.runOnIdle { inputMode.requestInputMode(InputMode.Keyboard) }
        rig.sheet.expand()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Collapse player").assertIsFocused()
        rig.sheet.collapse()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(MINI_BODY_TAG).assertIsFocused()
    }

    @Test
    fun `a drag while buffering is allowed`() {
        rig.show(loadedPlayerState(coverPath = NO_COVER, phase = PlayerPhase.Buffering, primary = PrimaryControl.Busy))
        val barTop =
            rig.morph.anchors.barBounds
                .shouldNotBeNull()
                .top
        composeRule.onNodeWithTag(MINI_BODY_TAG).performTouchInput {
            swipeUp(startY = center.y, endY = center.y - 0.7f * barTop, durationMillis = 1_200)
        }
        composeRule.waitForIdle()
        rig.sheet.expanded.value shouldBe true
    }

    @Test
    fun `a cover that has not loaded flies as its placeholder, and the morph does not restart`() {
        rig.show(loadedPlayerState(coverPath = "/nonexistent/slow.jpg"))
        rig.morph.snapDrag(0.5f)
        composeRule.waitForIdle()
        rig.morph.snapDrag(0.51f)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(MORPH_COVER_TAG, useUnmergedTree = true).assertExists()
        rig.morph.progress shouldBe 0.51f
        rig.morph.isDragging shouldBe true
    }
}
