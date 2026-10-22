package com.calypsan.listenup.client.design.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The one scrubber (§6): chapter-scoped, a slider to TalkBack, seeks once on release. Ports WavySeekBarTest. */
@RunWith(RobolectricTestRunner::class)
class PlaybackSliderTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var seeks = mutableListOf<Float>()

    private fun slider(enabled: Boolean = true) {
        composeRule.setContent {
            MaterialTheme {
                PlaybackSlider(
                    fraction = 0.586f,
                    elapsedMs = 724_000,
                    durationMs = 1_235_000,
                    onSeek = { seeks += it },
                    enabled = enabled,
                )
            }
        }
    }

    @Test
    fun `it is a slider named Chapter position, with elapsed of total as its state`() {
        slider()
        composeRule
            .onNodeWithContentDescription("Chapter position")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "12:04 of 20:35"))
    }

    @Test
    fun `the times read elapsed and minus remaining`() {
        slider()
        composeRule.onNodeWithText("12:04").assertExists()
        composeRule.onNodeWithText("-8:31").assertExists()
    }

    @Test
    fun `a set-progress action seeks once, to that fraction`() {
        slider()
        composeRule
            .onNodeWithContentDescription("Chapter position")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.25f) }
        composeRule.waitForIdle()
        seeks.size shouldBe 1
        seeks.single() shouldBe (0.25f plusOrMinus 0.001f)
    }

    @Test
    fun `disabled while busy`() {
        slider(enabled = false)
        composeRule.onNodeWithContentDescription("Chapter position").assertIsNotEnabled()
    }
}
