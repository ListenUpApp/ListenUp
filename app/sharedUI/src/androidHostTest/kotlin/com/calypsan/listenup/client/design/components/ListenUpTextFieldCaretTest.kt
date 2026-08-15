package com.calypsan.listenup.client.design.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [ListenUpTextField]'s value round-trips asynchronously (field → ViewModel `StateFlow` →
 * recomposition), so a recomposition can arrive carrying a STALE string while the user is still
 * typing. The `String`-overload `OutlinedTextField` treats the incoming string as authoritative
 * and re-clamps its internal selection against it — on a phone that is the "cannot put the cursor
 * at the end of the author field" bug, and under fast typing it silently drops whole bursts of
 * input.
 *
 * These tests pin the fix: the field owns its text + caret locally, ignores stale echoes of the
 * user's own keystrokes, and adopts only genuine external replacements (caret to the end).
 *
 * The lagging echo is simulated deterministically: `onValueChange` emissions are recorded and
 * played back into the hosted value via [androidx.compose.ui.test.junit4.ComposeContentTestRule
 * .runOnIdle] frames later — the same staleness a `LaunchedEffect { delay(...) }` round-trip
 * produces, without tying the test to Robolectric's clock.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
class ListenUpTextFieldCaretTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `fast typing survives a lagging echo with the caret at the end`() {
        var vmValue by mutableStateOf("")
        val emissions = mutableListOf<String>()
        composeRule.setContent {
            MaterialTheme {
                ListenUpTextField(
                    value = vmValue,
                    onValueChange = { emissions += it },
                    label = "Author",
                    modifier = Modifier.testTag(FIELD_TAG),
                )
            }
        }

        // Two bursts of typing while the caller's echo is still in flight (vmValue lags behind).
        composeRule.onNodeWithTag(FIELD_TAG).performTextInput("Bran")
        composeRule.onNodeWithTag(FIELD_TAG).performTextInput("don")

        // The echoes land late: first a stale one, then the up-to-date one.
        composeRule.runOnIdle { vmValue = emissions.first() }
        composeRule.waitForIdle()
        composeRule.runOnIdle { vmValue = emissions.last() }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(FIELD_TAG).assertTextContains("Brandon")
        composeRule.onNodeWithTag(FIELD_TAG).assertSelection(TextRange(7))
    }

    @Test
    fun `an external replacement is adopted with the caret at the end`() {
        var vmValue by mutableStateOf("Mistborn")
        composeRule.setContent {
            MaterialTheme {
                ListenUpTextField(
                    value = vmValue,
                    onValueChange = { vmValue = it },
                    label = "Title",
                    modifier = Modifier.testTag(FIELD_TAG),
                )
            }
        }

        // The ViewModel replaces the text wholesale — e.g. a metadata fetch filled the field.
        composeRule.runOnIdle { vmValue = "The Way of Kings" }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(FIELD_TAG).assertTextContains("The Way of Kings")
        composeRule.onNodeWithTag(FIELD_TAG).assertSelection(TextRange("The Way of Kings".length))
    }

    @Test
    fun `moving the caret without typing does not fire onValueChange`() {
        var vmValue by mutableStateOf("Brandon")
        var calls = 0
        composeRule.setContent {
            MaterialTheme {
                ListenUpTextField(
                    value = vmValue,
                    onValueChange = {
                        calls++
                        vmValue = it
                    },
                    label = "Author",
                    modifier = Modifier.testTag(FIELD_TAG),
                )
            }
        }

        composeRule.onNodeWithTag(FIELD_TAG).performClick()
        composeRule.onNodeWithTag(FIELD_TAG).performTextInputSelection(TextRange(2))
        composeRule.runOnIdle { calls shouldBe 0 }

        // The counter-case that stops the guard from over-suppressing: real typing still fires.
        composeRule.onNodeWithTag(FIELD_TAG).performTextInput("X")
        composeRule.runOnIdle { calls shouldBe 1 }
    }
}

private fun SemanticsNodeInteraction.assertSelection(expected: TextRange): SemanticsNodeInteraction = assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, expected))

private const val FIELD_TAG = "caret-test-field"
