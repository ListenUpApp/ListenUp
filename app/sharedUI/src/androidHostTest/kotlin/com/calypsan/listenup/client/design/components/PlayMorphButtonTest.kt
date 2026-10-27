package com.calypsan.listenup.client.design.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** P14: 96dp, square (checked) while playing, round while paused; busy keeps the button and shows an indicator. */
@RunWith(RobolectricTestRunner::class)
class PlayMorphButtonTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var presses = 0

    private fun button(
        isPlaying: Boolean,
        isBusy: Boolean = false,
    ) {
        composeRule.setContent {
            MaterialTheme {
                PlayMorphButton(
                    isPlaying = isPlaying,
                    isBusy = isBusy,
                    playLabel = "Play",
                    pauseLabel = "Pause",
                    busyLabel = "Loading. Pause",
                    onClick = { presses++ },
                )
            }
        }
    }

    @Test
    fun `playing reads Pause and is the checked, square shape`() {
        button(isPlaying = true)
        composeRule
            .onNodeWithContentDescription("Pause")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
            .assertWidthIsEqualTo(96.dp)
            .assertHeightIsEqualTo(96.dp)
    }

    @Test
    fun `paused reads Play and is unchecked, round`() {
        button(isPlaying = false)
        composeRule
            .onNodeWithContentDescription("Play")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
    }

    @Test
    fun `busy keeps the button's size and fill, its indicator uncontained in the glyph's place`() {
        button(isPlaying = false, isBusy = true)
        composeRule
            .onNodeWithContentDescription("Loading. Pause")
            .assertWidthIsEqualTo(96.dp)
            .assertHeightIsEqualTo(96.dp)
        composeRule.onNodeWithTag(PLAY_BUSY_INDICATOR_TAG, useUnmergedTree = true).assertExists()
    }

    @Test
    fun `busy reads Loading Pause and a press still reaches the player`() {
        button(isPlaying = true, isBusy = true)
        composeRule.onNodeWithContentDescription("Loading. Pause").performClick()
        presses shouldBe 1
    }
}
