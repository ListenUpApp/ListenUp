package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.client.design.motion.LocalReduceMotion
import com.calypsan.listenup.client.playback.PlayerPhase
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import com.calypsan.listenup.client.presentation.player.PrimaryControl
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A book switch moves the mini player's content out and back in (Simon, 2026-10-14): deliberate, never a one-frame
 * flicker, and never an empty bar. Under Remove animations it is an instant swap.
 */
@RunWith(RobolectricTestRunner::class)
class MiniPlayerBookSwitchTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var shown by mutableStateOf(book(id = "book-1", title = OLD_TITLE))

    @Test
    @Config(qualifiers = "w412dp-h915dp")
    fun `the phone bar moves the old book out and the new one in, and is never empty`() {
        render(reduceMotion = false) { PhoneBar() }
        assertMovesOutAndBackIn()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `the tablet toolbar moves the old book out and the new one in, and is never empty`() {
        render(reduceMotion = false) { Toolbar() }
        assertMovesOutAndBackIn()
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp")
    fun `under Remove animations the phone bar swaps books in one frame`() {
        render(reduceMotion = true) { PhoneBar() }
        assertSwapsInOneFrame()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `under Remove animations the toolbar swaps books in one frame`() {
        render(reduceMotion = true) { Toolbar() }
        assertSwapsInOneFrame()
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp")
    fun `a change within the same book does not move the bar`() {
        render(reduceMotion = false) { PhoneBar() }
        composeRule.mainClock.autoAdvance = false
        switchTo(book(id = "book-1", title = OLD_TITLE, phase = PlayerPhase.Paused, primary = PrimaryControl.Play))
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onAllNodesWithTag(MINI_BODY_TAG).fetchSemanticsNodes().size shouldBe 1
    }

    private fun assertMovesOutAndBackIn() {
        composeRule.mainClock.autoAdvance = false
        switchTo(book(id = "book-2", title = NEW_TITLE))
        var framesWithTheOldBook = 0
        repeat(TRANSITION_FRAMES) {
            composeRule.mainClock.advanceTimeByFrame()
            // The container never unmounts, and some book is always in it.
            composeRule.onAllNodesWithTag(BAR).fetchSemanticsNodes().size shouldBe 1
            val bodies = composeRule.onAllNodesWithTag(MINI_BODY_TAG).fetchSemanticsNodes().size
            bodies shouldBeGreaterThan 0
            if (titleShown(OLD_TITLE)) framesWithTheOldBook++
        }
        // The old book left over several frames: a deliberate move, not a cut.
        framesWithTheOldBook shouldBeGreaterThan 1
        composeRule.mainClock.advanceTimeBy(SETTLE_MS)
        composeRule.onNodeWithText(NEW_TITLE).assertExists()
        titleShown(OLD_TITLE) shouldBe false
    }

    private fun assertSwapsInOneFrame() {
        composeRule.mainClock.autoAdvance = false
        switchTo(book(id = "book-2", title = NEW_TITLE))
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onAllNodesWithTag(BAR).fetchSemanticsNodes().size shouldBe 1
        composeRule.onNodeWithText(NEW_TITLE).assertExists()
        titleShown(OLD_TITLE) shouldBe false
    }

    private fun switchTo(state: PlayerUiState.Loaded) {
        composeRule.runOnUiThread {
            shown = state
            Snapshot.sendApplyNotifications()
        }
    }

    private fun titleShown(title: String): Boolean = composeRule.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()

    private fun render(
        reduceMotion: Boolean,
        surface: @Composable () -> Unit,
    ) {
        composeRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalReduceMotion provides reduceMotion) { surface() }
            }
        }
    }

    @Composable
    private fun PhoneBar() {
        NowPlayingBar(
            state = shown,
            progress = { samplePlayerProgress },
            isExpanded = false,
            onTap = {},
            onPrimary = {},
            onSkipBack = {},
            onKeepListening = {},
            skipBackwardSec = 10,
            modifier = Modifier.testTag(BAR),
        )
    }

    @Composable
    private fun Toolbar() {
        NowPlayingToolbar(
            state = shown,
            progress = { samplePlayerProgress },
            isExpanded = false,
            onTap = {},
            onPrimary = {},
            onSkipBack = {},
            onSkipForward = {},
            onSeek = {},
            onSpeedClick = {},
            onSleepClick = {},
            onChaptersClick = {},
            onKeepListening = {},
            skipBackwardSec = 10,
            skipForwardSec = 30,
            modifier = Modifier.testTag(BAR),
        )
    }

    private companion object {
        const val BAR = "bar"
        const val OLD_TITLE = "The Way of Kings"
        const val NEW_TITLE = "Words of Radiance"

        // ~200ms at 60fps: well inside any M3 motion, so a cut shows here as the old book gone after one frame.
        const val TRANSITION_FRAMES = 12
        const val SETTLE_MS = 3_000L

        fun book(
            id: String,
            title: String,
            phase: PlayerPhase = PlayerPhase.Playing,
            primary: PrimaryControl = PrimaryControl.Pause,
        ): PlayerUiState.Loaded {
            val state = loadedPlayerState(title = title, phase = phase, primary = primary, coverPath = "/nonexistent/cover.jpg")
            return state.copy(book = state.book.copy(id = id))
        }
    }
}
