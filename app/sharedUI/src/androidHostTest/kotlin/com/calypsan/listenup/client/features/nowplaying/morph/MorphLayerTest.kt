package com.calypsan.listenup.client.features.nowplaying.morph

import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.DpRect
import com.calypsan.listenup.api.error.PlaybackError
import com.calypsan.listenup.client.design.components.PLAY_BUSY_INDICATOR_TAG
import com.calypsan.listenup.client.features.nowplaying.MINI_COVER_TAG
import com.calypsan.listenup.client.features.nowplaying.NO_COVER
import com.calypsan.listenup.client.features.nowplaying.components.PLAYER_COVER_TAG
import com.calypsan.listenup.client.features.nowplaying.loadedPlayerState
import com.calypsan.listenup.client.features.nowplaying.sheetStartsTinted
import com.calypsan.listenup.client.playback.PlayerPhase
import com.calypsan.listenup.client.presentation.player.MiniPlayerLines
import com.calypsan.listenup.client.presentation.player.MorphElement
import com.calypsan.listenup.client.presentation.player.MorphEnd
import com.calypsan.listenup.client.presentation.player.PrimaryControl
import com.calypsan.listenup.client.presentation.player.flight
import com.calypsan.listenup.client.presentation.player.reveal
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Canvas `MorphAndroid`: one progress places the flying parts, the sheet, the scrim and the navigation bar. */
@Config(qualifiers = "w412dp-h915dp")
@RunWith(RobolectricTestRunner::class)
class MorphLayerTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val rig = MorphRig(composeRule)

    private fun at(p: Float) {
        rig.morph.snapDrag(p)
        composeRule.waitForIdle()
        // A second pass: the page's anchors land after the frame that composed it.
        rig.morph.snapDrag(p + 1e-3f)
        composeRule.waitForIdle()
        rig.morph.snapDrag(p)
        composeRule.waitForIdle()
    }

    private fun layer(apply: GraphicsLayerScope.() -> Unit): GraphicsLayerScope = GraphicsLayerScope().apply(apply)

    private fun DpRect.px() = listOf(left.value, top.value, right.value, bottom.value).map { it * rig.density }

    @Test
    fun `at the bar the cover flies from the bar's cover, and the header has not begun to show`() {
        rig.show(loadedPlayerState(coverPath = NO_COVER))
        at(0f)
        val proxy = composeRule.onNodeWithTag(MORPH_COVER_TAG, useUnmergedTree = true).getBoundsInRoot().px()
        val bar = composeRule.onNodeWithTag(MINI_COVER_TAG, useUnmergedTree = true).getBoundsInRoot().px()
        proxy.zip(bar).forEach { (a, b) -> a shouldBe (b plusOrMinus 1.5f) }
        layer { revealLayer(rig.morph, MorphElement.Header) }.alpha shouldBe 0f
    }

    @Test
    fun `halfway the cover is on the arc, the scrim is at 16 %, and the navigation bar has gone`() {
        rig.show(loadedPlayerState(coverPath = NO_COVER))
        at(0.5f)
        val anchors = rig.morph.anchors
        val expected =
            flight(
                anchors.barRect(MorphPart.Cover).shouldNotBeNull().toMorphRect(),
                anchors.pageRect(MorphPart.Cover).shouldNotBeNull().toMorphRect(),
                0.5f,
            )
        val proxy = composeRule.onNodeWithTag(MORPH_COVER_TAG, useUnmergedTree = true).getBoundsInRoot()
        proxy.top.value * rig.density shouldBe (expected.top plusOrMinus 1f)
        layer { scrimLayer(rig.morph) }.alpha shouldBe (0.16f plusOrMinus 1e-4f)
        layer { navigationSlideLayer(rig.morph) }.translationY shouldBe
            (80f * rig.density * reveal(MorphElement.NavigationBar, 0.5f) plusOrMinus 0.5f)
    }

    @Test
    fun `at Now Playing the flying parts are gone and the real cover shows`() {
        rig.show(loadedPlayerState(coverPath = NO_COVER))
        at(0.9f)
        rig.morph.release(MorphEnd.NowPlaying, velocity = 0f)
        composeRule.waitForIdle()
        rig.morph.isInFlight shouldBe false
        composeRule.onNodeWithTag(MORPH_COVER_TAG, useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag(PLAYER_COVER_TAG, useUnmergedTree = true).assertExists()
        layer { sheetLayer(rig.morph) }.translationY shouldBe 0f
        rig.sheet.expanded.value shouldBe true
    }

    @Test
    fun `a busy button flies with the busy indicator riding inside`() {
        rig.show(loadedPlayerState(coverPath = NO_COVER, phase = PlayerPhase.Buffering, primary = PrimaryControl.Busy))
        at(0.5f)
        composeRule
            .onNode(hasTestTag(PLAY_BUSY_INDICATOR_TAG) and hasAnyAncestor(hasTestTag(MORPH_PLAY_TAG)), useUnmergedTree = true)
            .assertExists()
    }

    @Test
    fun `a problem card has no play button to fly into, and the sheet starts from the bar's error tint`() {
        val failed =
            loadedPlayerState(
                coverPath = NO_COVER,
                phase = PlayerPhase.Failed(PlaybackError.ConnectionLost()),
                primary = PrimaryControl.Retry,
            )
        rig.show(failed)
        at(0.5f)
        composeRule.onNodeWithTag(MORPH_PLAY_TAG, useUnmergedTree = true).assertDoesNotExist()
        playFlies(failed, MiniPlayerLines.of(failed)) shouldBe false
        sheetStartsTinted(failed) shouldBe true
        sheetStartsTinted(loadedPlayerState()) shouldBe false
    }
}
