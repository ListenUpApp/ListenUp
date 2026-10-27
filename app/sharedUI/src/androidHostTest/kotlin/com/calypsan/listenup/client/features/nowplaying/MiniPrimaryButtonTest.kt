package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.features.nowplaying.components.MiniPrimaryButton
import com.calypsan.listenup.client.presentation.player.PrimaryControl
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The mini player's one main button per state (`Main`, `Mini-AndroidTablet`, `States-Android`, `States-Lifecycle`). */
@RunWith(RobolectricTestRunner::class)
class MiniPrimaryButtonTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var presses = 0
    private var keepListeningPresses = 0

    private fun button(
        primary: PrimaryControl,
        playLabel: String? = null,
        isFadingOut: Boolean = false,
        size: DpSize = DpSize(56.dp, 48.dp),
    ) {
        val keepListening: () -> Unit = { keepListeningPresses++ }
        composeRule.setContent {
            MaterialTheme {
                MiniPrimaryButton(
                    primary = primary,
                    bookTitle = "The Way of Kings",
                    onClick = { presses++ },
                    size = size,
                    playLabel = playLabel,
                    onKeepListening = keepListening.takeIf { isFadingOut },
                )
            }
        }
    }

    private fun SemanticsNodeInteraction.isATarget() =
        apply {
            assertWidthIsAtLeast(48.dp)
            assertHeightIsAtLeast(48.dp)
        }

    @Test
    fun `pause and play are filled icon buttons that press through`() {
        button(PrimaryControl.Pause)
        composeRule.onNodeWithContentDescription("Pause").isATarget().performClick()
        presses shouldBe 1
    }

    @Test
    fun `play reads Play`() {
        button(PrimaryControl.Play)
        composeRule.onNodeWithContentDescription("Play").isATarget()
    }

    @Test
    fun `busy is a loading indicator that still pauses, named for both`() {
        button(PrimaryControl.Busy)
        composeRule.onNodeWithContentDescription("Loading. Pause").isATarget().performClick()
        presses shouldBe 1
    }

    @Test
    fun `busy keeps the play button's footprint at every size - phone bar, tablet toolbar, reader strip`() {
        var current by mutableStateOf<PrimaryControl>(PrimaryControl.Play)
        var footprint by mutableStateOf(DpSize(56.dp, 48.dp))
        composeRule.setContent {
            MaterialTheme {
                MiniPrimaryButton(primary = current, bookTitle = "The Way of Kings", onClick = { presses++ }, size = footprint)
            }
        }
        listOf(DpSize(56.dp, 48.dp), DpSize(72.dp, 56.dp), DpSize(48.dp, 48.dp)).forEach { size ->
            footprint = size
            current = PrimaryControl.Play
            composeRule.waitForIdle()
            val play = composeRule.onNodeWithContentDescription("Play").getUnclippedBoundsInRoot()
            current = PrimaryControl.Busy
            composeRule.waitForIdle()
            val busy = composeRule.onNodeWithContentDescription("Loading. Pause")
            busy.getUnclippedBoundsInRoot() shouldBe play
            busy.assertWidthIsEqualTo(size.width).assertHeightIsEqualTo(size.height)
            busy.performClick()
        }
        presses shouldBe 3
    }

    @Test
    fun `retry is a text button`() {
        button(PrimaryControl.Retry)
        composeRule.onNodeWithText("Retry").isATarget().performClick()
        presses shouldBe 1
    }

    @Test
    fun `sign in says what it does`() {
        button(PrimaryControl.SignIn)
        composeRule.onNodeWithText("Sign in").isATarget()
    }

    @Test
    fun `open inbox says what it does`() {
        button(PrimaryControl.OpenInbox)
        composeRule.onNodeWithText("Open Inbox").isATarget()
    }

    @Test
    fun `listen again names the book it replays`() {
        button(PrimaryControl.ListenAgain)
        composeRule.onNodeWithContentDescription("Listen again to The Way of Kings").isATarget()
        composeRule.onNodeWithText("Listen again", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `close is a cross named Close book`() {
        button(PrimaryControl.Close)
        composeRule.onNodeWithContentDescription("Close book").isATarget()
    }

    @Test
    fun `a background refusal's play reads Resume`() {
        button(PrimaryControl.Play, playLabel = "Resume")
        composeRule.onNodeWithContentDescription("Resume").isATarget()
    }

    @Test
    fun `during the sleep fade the button is Keep listening, which cancels the timer instead of pausing`() {
        button(PrimaryControl.Pause, isFadingOut = true)
        composeRule.onNodeWithContentDescription("Pause").assertDoesNotExist()
        composeRule.onNodeWithText("Keep listening").isATarget().performClick()
        keepListeningPresses shouldBe 1
        presses shouldBe 0
    }
}
