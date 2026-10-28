package com.calypsan.listenup.client.features.nowplaying.morph

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.calypsan.listenup.client.features.nowplaying.MINI_BODY_TAG
import com.calypsan.listenup.client.features.nowplaying.NO_COVER
import com.calypsan.listenup.client.features.nowplaying.NowPlayingLayout
import com.calypsan.listenup.client.features.nowplaying.components.PLAYER_COVER_TAG
import com.calypsan.listenup.client.features.nowplaying.loadedPlayerState
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Never stranded: a drag whose surface goes away mid-gesture (the window widens into the toolbar, the book unloads,
 * Now Playing's content is replaced) settles by position, and the tap, Collapse and back keep working.
 */
@Config(qualifiers = "w412dp-h915dp")
@RunWith(RobolectricTestRunner::class)
class MorphStuckDragTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val rig = MorphRig(composeRule)

    private val barTop: Float get() =
        rig.morph.anchors.barBounds
            .shouldNotBeNull()
            .top

    private fun holdBarAt(fraction: Float) {
        composeRule.onNodeWithTag(MINI_BODY_TAG).performTouchInput {
            down(center)
            repeat(8) { moveBy(Offset(0f, -fraction * barTop / 8f), delayMillis = 40) }
        }
        composeRule.waitForIdle()
        rig.morph.isDragging shouldBe true
    }

    private fun openFully() {
        rig.sheet.expand()
        composeRule.waitForIdle()
        rig.morph.progress shouldBe 1f
    }

    private fun holdSheetAt(fraction: Float) {
        composeRule.onNodeWithTag(PLAYER_COVER_TAG, useUnmergedTree = true).performTouchInput {
            down(center)
            repeat(8) { moveBy(Offset(0f, fraction * barTop / 8f), delayMillis = 40) }
        }
        composeRule.waitForIdle()
        rig.morph.isDragging shouldBe true
    }

    private fun liftFinger() {
        composeRule.onRoot().performTouchInput { up() }
        composeRule.waitForIdle()
    }

    private fun assertAtRest() {
        rig.morph.isDragging shouldBe false
        rig.morph.isInFlight shouldBe false
        (rig.morph.progress == 0f || rig.morph.progress == 1f) shouldBe true
    }

    @Test
    fun `the window widening into the toolbar mid-drag lets go, and the player still opens and closes`() {
        rig.show(loadedPlayerState(coverPath = NO_COVER))
        holdBarAt(0.4f)
        rig.usesPhoneBar = false
        rig.layout = NowPlayingLayout.SideBySide
        composeRule.waitForIdle()
        assertAtRest()
        liftFinger()
        rig.sheet.expand()
        composeRule.waitForIdle()
        rig.morph.progress shouldBe 1f
        rig.visibility.currentState shouldBe true
        rig.sheet.collapse()
        composeRule.waitForIdle()
        rig.morph.progress shouldBe 0f
        rig.visibility.currentState shouldBe false
    }

    @Test
    fun `the book unloading mid-drag settles the morph back on the bar`() {
        rig.show(loadedPlayerState(coverPath = NO_COVER))
        holdBarAt(0.4f)
        rig.state = PlayerUiState.NothingLoaded
        composeRule.waitForIdle()
        assertAtRest()
        rig.morph.progress shouldBe 0f
        liftFinger()
    }

    @Test
    fun `Now Playing's content going away mid-close lets go, and Collapse still works`() {
        rig.show(loadedPlayerState(coverPath = NO_COVER))
        openFully()
        holdSheetAt(0.4f)
        rig.usesPhoneBar = false
        rig.layout = NowPlayingLayout.SideBySide
        composeRule.waitForIdle()
        assertAtRest()
        rig.morph.progress shouldBe 1f
        liftFinger()
        composeRule.onNodeWithContentDescription("Collapse player").performClick()
        composeRule.waitForIdle()
        rig.sheet.expanded.value shouldBe false
        rig.morph.progress shouldBe 0f
    }

    @Test
    fun `the book unloading mid-close settles the morph`() {
        rig.show(loadedPlayerState(coverPath = NO_COVER))
        openFully()
        holdSheetAt(0.4f)
        rig.state = PlayerUiState.NothingLoaded
        composeRule.waitForIdle()
        assertAtRest()
        liftFinger()
    }
}
