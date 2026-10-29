package com.calypsan.listenup.client.features.nowplaying.morph

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeWithVelocity
import com.calypsan.listenup.api.error.PlaybackError
import com.calypsan.listenup.client.features.nowplaying.NO_COVER
import com.calypsan.listenup.client.features.nowplaying.components.PLAYER_COVER_TAG
import com.calypsan.listenup.client.features.nowplaying.loadedPlayerState
import com.calypsan.listenup.client.playback.PlayerPhase
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import com.calypsan.listenup.client.presentation.player.PrimaryControl
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Canvas `GestureAndroid`, DRAG DOWN TO CLOSE: the same rule as opening, scroll first, never from the scrubber. */
@Config(qualifiers = "w412dp-h915dp")
@RunWith(RobolectricTestRunner::class)
class NowPlayingDragCloseTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val rig = MorphRig(composeRule)

    private fun open(state: PlayerUiState.Loaded = loadedPlayerState(coverPath = NO_COVER)) {
        rig.show(state)
        rig.sheet.expand()
        composeRule.waitForIdle()
        rig.morph.progress shouldBe 1f
    }

    private val barTop: Float get() =
        rig.morph.anchors.barBounds
            .shouldNotBeNull()
            .top

    @Test
    fun `a slow drag down past halfway closes`() {
        open()
        composeRule.onNodeWithTag(PLAYER_COVER_TAG, useUnmergedTree = true).performTouchInput {
            swipeDown(startY = center.y, endY = center.y + 0.6f * barTop, durationMillis = 1_500)
        }
        composeRule.waitForIdle()
        rig.sheet.expanded.value shouldBe false
        rig.morph.progress shouldBe 0f
    }

    @Test
    fun `a slow drag down short of halfway springs back open`() {
        open()
        composeRule.onNodeWithTag(PLAYER_COVER_TAG, useUnmergedTree = true).performTouchInput {
            swipeDown(startY = center.y, endY = center.y + 0.3f * barTop, durationMillis = 1_500)
        }
        composeRule.waitForIdle()
        rig.sheet.expanded.value shouldBe true
        rig.morph.progress shouldBe 1f
    }

    @Test
    fun `a fast flick down closes from 80 percent`() {
        open()
        composeRule.onNodeWithTag(PLAYER_COVER_TAG, useUnmergedTree = true).performTouchInput {
            swipeWithVelocity(start = center, end = center + Offset(0f, 0.2f * barTop), endVelocity = 1_500f * rig.density)
        }
        composeRule.waitForIdle()
        rig.sheet.expanded.value shouldBe false
    }

    @Test
    fun `a vertical drag that starts on the scrubber does not move the morph`() {
        open()
        composeRule.onNodeWithContentDescription("Chapter position").performTouchInput {
            swipeDown(startY = center.y, endY = center.y + 0.6f * barTop, durationMillis = 1_500)
        }
        composeRule.waitForIdle()
        rig.sheet.expanded.value shouldBe true
        rig.morph.progress shouldBe 1f
    }

    @Test
    @Config(qualifiers = "w412dp-h800dp", fontScale = 2f)
    fun `when the player overflows, a drag down scrolls it back up before it starts to close`() {
        open(
            loadedPlayerState(
                coverPath = NO_COVER,
                phase = PlayerPhase.Failed(PlaybackError.ConnectionLost()),
                primary = PrimaryControl.Retry,
            ),
        )
        composeRule.onNodeWithContentDescription("Chapters").performScrollTo()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Chapters").performTouchInput {
            swipeDown(startY = center.y, endY = center.y + 120f * rig.density, durationMillis = 600)
        }
        composeRule.waitForIdle()
        rig.morph.progress shouldBe 1f
        rig.sheet.expanded.value shouldBe true
    }

    @Test
    fun `Collapse runs the frames backwards on the default spatial spring`() {
        open()
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithContentDescription("Collapse player").performClick()
        composeRule.mainClock.advanceTimeBy(100)
        rig.morph.progress shouldBeGreaterThan 0f
        rig.morph.progress shouldBeLessThan 1f
        composeRule.mainClock.advanceTimeBy(1_000)
        rig.morph.progress shouldBe 0f
        rig.sheet.expanded.value shouldBe false
    }
}
