package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.calypsan.listenup.client.playback.PlayerPhase
import com.calypsan.listenup.client.playback.SleepTimerMode
import com.calypsan.listenup.client.playback.SleepTimerState
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import com.calypsan.listenup.client.presentation.player.PrimaryControl
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import androidx.compose.ui.text.style.TextDirection
import com.calypsan.listenup.client.design.text.ThemedFor
import com.calypsan.listenup.client.design.text.textDirection
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The tablet floating toolbar (`Mini-AndroidTablet`): the wide control set, width-driven. */
@RunWith(RobolectricTestRunner::class)
class NowPlayingToolbarTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val seeks = mutableListOf<Float>()
    private var sleepTaps = 0
    private var chapterTaps = 0
    private var primaryPresses = 0
    private var keepListeningPresses = 0

    private fun toolbar(
        state: PlayerUiState.Loaded = loadedPlayerState(phase = PlayerPhase.Playing),
        rtl: Boolean = false,
    ) {
        composeRule.setContent {
            ThemedFor(rtl) {
                Box(Modifier.fillMaxSize()) {
                    NowPlayingToolbar(
                        state = state.copy(book = state.book.copy(coverPath = "/nonexistent/cover.jpg")),
                        progress = { samplePlayerProgress },
                        isExpanded = false,
                        onTap = {},
                        onPrimary = { primaryPresses++ },
                        onSkipBack = {},
                        onSkipForward = {},
                        onSeek = { seeks += it },
                        onSpeedClick = {},
                        onSleepClick = { sleepTaps++ },
                        onChaptersClick = { chapterTaps++ },
                        onKeepListening = { keepListeningPresses++ },
                        skipBackwardSec = 10,
                        skipForwardSec = 30,
                        modifier = Modifier.align(Alignment.BottomCenter).testTag(TOOLBAR),
                    )
                }
            }
        }
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `in a right-to-left layout the toolbar's rate and times read left to right`() {
        toolbar(rtl = true)
        composeRule.onNodeWithText("1.0×", useUnmergedTree = true).textDirection() shouldBe TextDirection.ContentOrLtr
        composeRule.onNodeWithText("-8:31", useUnmergedTree = true).textDirection() shouldBe TextDirection.ContentOrLtr
    }

    private fun SemanticsNodeInteraction.isATarget() =
        apply {
            assertWidthIsAtLeast(48.dp)
            assertHeightIsAtLeast(48.dp)
        }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `at tablet width it carries the whole wide set, each a full target`() {
        toolbar()
        composeRule.onNodeWithContentDescription("Skip back 10 seconds").isATarget()
        composeRule.onNodeWithContentDescription("Pause").isATarget()
        composeRule.onNodeWithContentDescription("Skip forward 30 seconds").isATarget()
        composeRule.onNodeWithContentDescription("Chapter position").assertExists()
        composeRule.onNode(hasContentDescription("Playback speed", substring = true)).isATarget()
        composeRule.onNodeWithContentDescription("Sleep timer").isATarget()
        composeRule.onNodeWithContentDescription("Chapters").isATarget()
        composeRule.onNodeWithContentDescription("Open Now Playing").isATarget()
        // The book's own chapter title, never our counter (Simon, 2026-10-14); the times sit beside the scrubber.
        composeRule.onNodeWithText("Bridge Four", useUnmergedTree = true).assertExists()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `the toolbar hugs its one row, however many controls it carries`() {
        toolbar()
        composeRule.onNodeWithTag(TOOLBAR).getUnclippedBoundsInRoot().height shouldBeLessThanOrEqualTo ToolbarMaxHeight
    }

    @Test
    @Config(qualifiers = "w700dp-h1000dp")
    fun `too narrow for the whole set, the scrubber and the settings step aside`() {
        toolbar()
        composeRule.onNodeWithContentDescription("Pause").assertExists()
        composeRule.onNodeWithContentDescription("Skip forward 30 seconds").assertExists()
        composeRule.onNodeWithContentDescription("Open Now Playing").assertExists()
        composeRule.onNodeWithContentDescription("Chapter position").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Chapters").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Sleep timer").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `an armed timer shows its countdown in place of the moon`() {
        val timer = SleepTimerState.Active(SleepTimerMode.Duration(30), remainingMs = 1_380_000, totalMs = 1_800_000, startedAt = 0)
        toolbar(loadedPlayerState(phase = PlayerPhase.Playing, sleepTimer = timer))
        composeRule.onNodeWithText("23:00", useUnmergedTree = true).assertExists()
        composeRule.onNode(hasContentDescription("Sleep timer, 23:00 left")).performClick()
        sleepTaps shouldBe 1
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `chapters opens the chapter sheet`() {
        toolbar()
        composeRule.onNodeWithContentDescription("Chapters").performClick()
        chapterTaps shouldBe 1
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `the scrubber is chapter-scoped and seeks once, on release`() {
        toolbar()
        composeRule.onNodeWithContentDescription("Chapter position").performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        seeks shouldBe listOf(0.5f)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `busy hides back and keeps forward`() {
        toolbar(loadedPlayerState(phase = PlayerPhase.Buffering, primary = PrimaryControl.Busy))
        composeRule.onNodeWithContentDescription("Skip back 10 seconds").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Loading. Pause").assertExists()
        composeRule.onNodeWithContentDescription("Skip forward 30 seconds").assertExists()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `a dropped connection names the busy control Reconnecting`() {
        toolbar(loadedPlayerState(phase = PlayerPhase.Reconnecting, primary = PrimaryControl.Busy))
        composeRule.onNodeWithText("Reconnecting…", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithContentDescription("Reconnecting. Pause").assertExists()
        composeRule.onNodeWithContentDescription("Skip back 10 seconds").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `the sleep fade offers Keep listening in place of Pause, and keeps back`() {
        toolbar(loadedPlayerState(phase = PlayerPhase.SleepFading))
        composeRule.onNodeWithContentDescription("Skip back 10 seconds").assertExists()
        composeRule.onNodeWithContentDescription("Pause").assertDoesNotExist()
        composeRule.onNode(hasText("Keep listening")).performClick()
        keepListeningPresses shouldBe 1
        primaryPresses shouldBe 0
    }

    private companion object {
        const val TOOLBAR = "toolbar"

        // The board's toolbar: a 60dp cover in a 72dp body, inside the toolbar's 8dp padding (`Mini-AndroidTablet`).
        val ToolbarMaxHeight = 96.dp
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `an armed end-of-chapter timer counts down to the chapter's end, not 0 00`() {
        val timer = SleepTimerState.Active(SleepTimerMode.EndOfChapter, remainingMs = 0, totalMs = 0, startedAt = 0)
        toolbar(loadedPlayerState(phase = PlayerPhase.Playing, sleepTimer = timer))
        // samplePlayerProgress: 724s into a 1,235s chapter, so 8:31 left at 1.0×.
        composeRule.onNode(hasContentDescription("Sleep timer, 8:31 left")).assertExists()
    }
}
