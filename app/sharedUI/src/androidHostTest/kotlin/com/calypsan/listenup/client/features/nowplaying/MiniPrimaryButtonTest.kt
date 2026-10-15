package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHeightIsAtLeast
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
    ) {
        val keepListening: () -> Unit = { keepListeningPresses++ }
        composeRule.setContent {
            MaterialTheme {
                MiniPrimaryButton(
                    primary = primary,
                    bookTitle = "The Way of Kings",
                    onClick = { presses++ },
                    size = DpSize(56.dp, 48.dp),
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
