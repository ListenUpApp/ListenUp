package com.calypsan.listenup.client.design.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextLayoutResult
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

    @Test
    fun `a caller that never echoes keeps every keystroke past the ledger cap`() {
        // Pathological caller: onValueChange is discarded and the value stays pinned at "", so no
        // echo ever arrives and the ledger can only shed entries through its cap. Typing past the
        // cap must neither lose text nor let the pinned value read as an external replacement.
        composeRule.setContent {
            MaterialTheme {
                ListenUpTextField(
                    value = "",
                    onValueChange = {},
                    label = "Author",
                    modifier = Modifier.testTag(FIELD_TAG),
                )
            }
        }

        val typed = MAX_TRACKED_ECHOES + 8
        repeat(typed) {
            composeRule.onNodeWithTag(FIELD_TAG).performTextInput("a")
        }

        composeRule.onNodeWithTag(FIELD_TAG).assertTextContains("a".repeat(typed))
        composeRule.onNodeWithTag(FIELD_TAG).assertSelection(TextRange(typed))
    }

    @Test
    fun `a transform runs inside the component so echoes stay verbatim and the caret holds`() {
        var vmValue by mutableStateOf("")
        val emissions = mutableListOf<String>()
        composeRule.setContent {
            MaterialTheme {
                ListenUpTextField(
                    value = vmValue,
                    onValueChange = { emissions += it },
                    label = "Year",
                    transform = { it.filter(Char::isDigit).take(4) },
                    modifier = Modifier.testTag(FIELD_TAG),
                )
            }
        }

        // Mixed input: the transform strips the stray letter before the ledger or caller see it.
        composeRule.onNodeWithTag(FIELD_TAG).performTextInput("19")
        composeRule.onNodeWithTag(FIELD_TAG).performTextInput("x8")
        composeRule.onNodeWithTag(FIELD_TAG).performTextInput("4")

        // Only transformed text ever reaches the caller — never the raw keystrokes.
        composeRule.runOnIdle { emissions shouldBe listOf("19", "198", "1984") }

        // The echoes land late, stale-first: verbatim by construction, so the field must not churn.
        composeRule.runOnIdle { vmValue = emissions.first() }
        composeRule.waitForIdle()
        composeRule.runOnIdle { vmValue = emissions.last() }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(FIELD_TAG).assertTextContains("1984")
        composeRule.onNodeWithTag(FIELD_TAG).assertSelection(TextRange(4))
    }

    @Test
    fun `trailingContent wins over trailingIcon and onTrailingClick`() {
        composeRule.setContent {
            MaterialTheme {
                ListenUpTextField(
                    value = "",
                    onValueChange = {},
                    label = "Search",
                    trailingIcon = Icons.Default.Search,
                    onTrailingClick = {},
                    trailingContent = { Text("custom", modifier = Modifier.testTag(TRAILING_TAG)) },
                    modifier = Modifier.testTag(FIELD_TAG),
                )
            }
        }

        composeRule.onNodeWithTag(TRAILING_TAG, useUnmergedTree = true).assertExists()
    }

    @Test
    fun `the field defaults to a single line so long text scrolls instead of wrapping`() {
        composeRule.setContent {
            MaterialTheme {
                ListenUpTextField(
                    value = "",
                    onValueChange = {},
                    label = "Author",
                    modifier = Modifier.testTag(FIELD_TAG),
                )
            }
        }

        // Far wider than the field: a multiline default would soft-wrap this onto several lines.
        composeRule.onNodeWithTag(FIELD_TAG).performTextInput("word ".repeat(40))

        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithTag(FIELD_TAG).performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            it(layouts)
        }
        layouts.first().lineCount shouldBe 1
    }
}

private fun SemanticsNodeInteraction.assertSelection(expected: TextRange): SemanticsNodeInteraction = assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, expected))

private const val FIELD_TAG = "caret-test-field"

private const val TRAILING_TAG = "caret-test-trailing"
