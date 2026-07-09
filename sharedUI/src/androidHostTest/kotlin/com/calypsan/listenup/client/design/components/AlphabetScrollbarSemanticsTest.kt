package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Verifies that [AlphabetScrollbar] exposes the accessibility semantics required for
 * TalkBack users to perceive the fast-scroll control.
 *
 * Two properties are asserted:
 *  - `ContentDescription` — identifies the widget to screen readers as "Alphabet scrollbar".
 *  - `StateDescription` — reflects the focused letter when one is active during touch
 *    (e.g. "Letter A"), and is absent at rest.
 *
 * JUnit4 + Robolectric (consistent with [WavySeekBarTest]).
 */
@RunWith(RobolectricTestRunner::class)
class AlphabetScrollbarSemanticsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val testAlphabetIndex =
        AlphabetIndex(
            letters = listOf('A', 'B', 'C'),
            letterToIndex = mapOf('A' to 0, 'B' to 1, 'C' to 2),
        )

    @Test
    fun `AlphabetScrollbar exposes contentDescription identifying it as alphabet scrollbar`() {
        composeRule.setContent {
            Box(Modifier.fillMaxSize()) {
                AlphabetScrollbar(
                    alphabetIndex = testAlphabetIndex,
                    onLetterSelected = {},
                    isScrolling = true,
                    modifier = Modifier.testTag(TEST_TAG),
                )
            }
        }

        composeRule.waitForIdle()

        composeRule
            .onNodeWithTag(TEST_TAG)
            .assert(
                SemanticsMatcher("has contentDescription 'Alphabet scrollbar'") { node ->
                    val descriptions = node.config.getOrNull(SemanticsProperties.ContentDescription)
                    descriptions != null && descriptions.any { it == "Alphabet scrollbar" }
                },
            )
    }

    @Test
    fun `AlphabetScrollbar stateDescription names active letter when one is focused`() {
        composeRule.setContent {
            Box(Modifier.fillMaxSize()) {
                AlphabetScrollbar(
                    alphabetIndex = testAlphabetIndex,
                    onLetterSelected = {},
                    isScrolling = true,
                    modifier = Modifier.testTag(TEST_TAG),
                )
            }
        }

        composeRule.waitForIdle()

        // Press and hold to activate letter selection (do not release — that clears selectedLetter)
        composeRule
            .onNodeWithTag(TEST_TAG)
            .performTouchInput { down(center) }

        composeRule.waitForIdle()

        // With a letter now selected, stateDescription should name it
        composeRule
            .onNodeWithTag(TEST_TAG)
            .assert(
                SemanticsMatcher("stateDescription names a letter while touch is held") { node ->
                    val description = node.config.getOrNull(SemanticsProperties.StateDescription)
                    description != null && description.startsWith("Letter ")
                },
            )

        // Release the touch to restore state
        composeRule
            .onNodeWithTag(TEST_TAG)
            .performTouchInput { up() }
    }

    private companion object {
        const val TEST_TAG = "alphabet_scrollbar_test"
    }
}
