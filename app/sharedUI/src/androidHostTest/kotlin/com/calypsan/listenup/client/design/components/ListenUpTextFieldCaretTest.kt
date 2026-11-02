package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
 * [ListenUpTextField] holds a screen-owned `TextFieldState`: the field owns the text and the caret
 * and tells the screen about edits, so nothing round-trips through a ViewModel while the person
 * types. A `String`-overload field treated a late, stale echo of the user's own keystroke as
 * authoritative and re-clamped the caret against it ("cannot put the cursor at the end of the
 * author field"), dropping whole bursts of input under latency.
 *
 * These tests pin the guarantees that fix must keep: fast typing survives a slow collector with the
 * caret at the end, a replacement the screen makes lands with the caret at the end, caret moves are
 * silent, an in-field transformation holds the caret and a fully rejected edit says nothing.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
class ListenUpTextFieldCaretTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `fast typing survives a slow collector with the caret at the end`() {
        val emissions = mutableListOf<String>()
        composeRule.setContent {
            MaterialTheme {
                ListenUpTextField(
                    state = rememberForwardingTextFieldState("") { emissions += it },
                    label = "Author",
                    modifier = Modifier.testTag(FIELD_TAG),
                )
            }
        }

        // Two bursts of typing; the collector (the ViewModel side) only sees them frames later.
        composeRule.onNodeWithTag(FIELD_TAG).performTextInput("Bran")
        composeRule.onNodeWithTag(FIELD_TAG).performTextInput("don")
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(FIELD_TAG).assertTextContains("Brandon")
        composeRule.onNodeWithTag(FIELD_TAG).assertSelection(TextRange(7))
        composeRule.runOnIdle { emissions.last() shouldBe "Brandon" }
    }

    @Test
    fun `a replacement the screen makes is adopted with the caret at the end`() {
        lateinit var state: TextFieldState
        composeRule.setContent {
            MaterialTheme {
                state = rememberTextFieldState("Mistborn")
                ListenUpTextField(
                    state = state,
                    label = "Title",
                    modifier = Modifier.testTag(FIELD_TAG),
                )
            }
        }

        // The screen replaces the text wholesale — e.g. a metadata fetch filled the field.
        composeRule.runOnIdle { state.setTextAndPlaceCursorAtEnd("The Way of Kings") }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(FIELD_TAG).assertTextContains("The Way of Kings")
        composeRule.onNodeWithTag(FIELD_TAG).assertSelection(TextRange("The Way of Kings".length))
    }

    @Test
    fun `moving the caret without typing does not report an edit`() {
        var calls = 0
        composeRule.setContent {
            MaterialTheme {
                ListenUpTextField(
                    state = rememberForwardingTextFieldState("Brandon") { calls++ },
                    label = "Author",
                    modifier = Modifier.testTag(FIELD_TAG),
                )
            }
        }

        composeRule.onNodeWithTag(FIELD_TAG).performClick()
        composeRule.onNodeWithTag(FIELD_TAG).performTextInputSelection(TextRange(2))
        composeRule.runOnIdle { calls shouldBe 0 }

        // The counter-case that stops the guard from over-suppressing: real typing still reports.
        composeRule.onNodeWithTag(FIELD_TAG).performTextInput("X")
        composeRule.runOnIdle { calls shouldBe 1 }
    }

    @Test
    fun `a collector that never reads keeps every keystroke`() {
        // Pathological screen: nobody listens at all, so nothing is ever acknowledged. Typing a long
        // run must neither lose text nor move the caret off the end.
        composeRule.setContent {
            MaterialTheme {
                ListenUpTextField(
                    state = rememberTextFieldState(),
                    label = "Author",
                    modifier = Modifier.testTag(FIELD_TAG),
                )
            }
        }

        val typed = 40
        repeat(typed) {
            composeRule.onNodeWithTag(FIELD_TAG).performTextInput("a")
        }

        composeRule.onNodeWithTag(FIELD_TAG).assertTextContains("a".repeat(typed))
        composeRule.onNodeWithTag(FIELD_TAG).assertSelection(TextRange(typed))
    }

    @Test
    fun `a transformation runs inside the field so the screen only hears clean text and the caret holds`() {
        val emissions = mutableListOf<String>()
        composeRule.setContent {
            MaterialTheme {
                ListenUpTextField(
                    state = rememberForwardingTextFieldState("") { emissions += it },
                    label = "Year",
                    inputTransformation = transformedBy { it.filter(Char::isDigit).take(4) },
                    modifier = Modifier.testTag(FIELD_TAG),
                )
            }
        }

        // Mixed input: the transformation strips the stray letter before the screen ever sees it.
        composeRule.onNodeWithTag(FIELD_TAG).performTextInput("19")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(FIELD_TAG).performTextInput("x8")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(FIELD_TAG).performTextInput("4")
        composeRule.waitForIdle()

        // Only transformed text ever reaches the screen — never the raw keystrokes.
        composeRule.runOnIdle { emissions shouldBe listOf("19", "198", "1984") }
        composeRule.onNodeWithTag(FIELD_TAG).assertTextContains("1984")
        composeRule.onNodeWithTag(FIELD_TAG).assertSelection(TextRange(4))
    }

    @Test
    fun `a fully-rejected edit reports nothing`() {
        var calls = 0
        composeRule.setContent {
            MaterialTheme {
                ListenUpTextField(
                    state = rememberForwardingTextFieldState("") { calls++ },
                    label = "Year",
                    inputTransformation = transformedBy { it.filter(Char::isDigit).take(4) },
                    modifier = Modifier.testTag(FIELD_TAG),
                )
            }
        }

        // A single rejected character: the transformation reverts the edit, so the screen hears
        // nothing at all — not even an unchanged text.
        composeRule.onNodeWithTag(FIELD_TAG).performTextInput("x")
        composeRule.runOnIdle { calls shouldBe 0 }

        // Counter-case that keeps the guard honest: an accepted character still reports.
        composeRule.onNodeWithTag(FIELD_TAG).performTextInput("7")
        composeRule.runOnIdle { calls shouldBe 1 }
    }

    @Test
    fun `a password field is a secure field and an ordinary one is not`() {
        composeRule.setContent {
            MaterialTheme {
                Column {
                    ListenUpTextField(
                        state = rememberTextFieldState("hunter2"),
                        label = "Password",
                        obfuscation = TextObfuscationMode.Hidden,
                        modifier = Modifier.testTag(FIELD_TAG),
                    )
                    ListenUpTextField(
                        state = rememberTextFieldState("visible"),
                        label = "Name",
                        modifier = Modifier.testTag(TRAILING_TAG),
                    )
                }
            }
        }

        composeRule.onNodeWithTag(FIELD_TAG).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        composeRule.onNodeWithTag(TRAILING_TAG).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Password))
    }

    @Test
    fun `trailingContent wins over trailingIcon and onTrailingClick`() {
        composeRule.setContent {
            MaterialTheme {
                ListenUpTextField(
                    state = rememberTextFieldState(),
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
                    state = rememberTextFieldState(),
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

private fun SemanticsNodeInteraction.assertSelection(expected: TextRange): SemanticsNodeInteraction =
    assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, expected))

private const val FIELD_TAG = "caret-test-field"

private const val TRAILING_TAG = "caret-test-trailing"
