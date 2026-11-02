package com.calypsan.listenup.client.features.auth.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import com.calypsan.listenup.client.design.components.rememberForwardingTextFieldState
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [CodeBoxes] normalises its input (`normalizedCode()`) inside the field. With the old `String`
 * overload the caller's echo NEVER equalled what was typed, so the internal buffer was reset
 * against the stale caller value on every recomposition and whole bursts of typed characters were
 * silently dropped (masked only because the caret is invisible).
 *
 * These tests pin the fix: the screen owns a `TextFieldState` and the field normalises as an input
 * transformation, so typing survives a slow collector, while a replacement the screen makes (e.g.
 * clearing a rejected code) is still adopted.
 */
@RunWith(RobolectricTestRunner::class)
class CodeBoxesTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `fast typing survives a slow collector and lands normalised`() {
        val emissions = mutableListOf<String>()
        composeRule.setContent {
            MaterialTheme {
                CodeBoxes(state = rememberForwardingTextFieldState("") { emissions += it })
            }
        }

        // Two bursts of typing — lower case and the spoken separator, exactly what a person keys
        // while a code is read to them.
        composeRule.onNodeWithTag(CODE_FIELD_TAG).performTextInput("k4m9")
        composeRule.onNodeWithTag(CODE_FIELD_TAG).performTextInput("-tqw")
        composeRule.waitForIdle()

        // The second burst appended to the first — nothing was dropped, everything normalised.
        composeRule.runOnIdle { emissions.last() shouldBe "K4M9TQW" }
        composeRule.onNodeWithText("K").assertExists()
        composeRule.onNodeWithText("W").assertExists()
    }

    @Test
    fun `a pasted code is folded to the alphabet and capped at eight`() {
        val emissions = mutableListOf<String>()
        composeRule.setContent {
            MaterialTheme {
                CodeBoxes(state = rememberForwardingTextFieldState("") { emissions += it })
            }
        }

        composeRule.onNodeWithTag(CODE_FIELD_TAG).performTextInput("k4m9-tq-wz99")
        composeRule.waitForIdle()

        composeRule.runOnIdle { emissions.last() shouldBe "K4M9TQWZ" }
    }

    @Test
    fun `an external clear empties the cells`() {
        lateinit var state: TextFieldState
        composeRule.setContent {
            MaterialTheme {
                state = rememberTextFieldState()
                CodeBoxes(state = state)
            }
        }

        composeRule.onNodeWithTag(CODE_FIELD_TAG).performTextInput("k4m9")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("K").assertExists()

        // The screen clears the code — a genuine replacement, not an echo.
        composeRule.runOnIdle { state.clearText() }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("K").assertDoesNotExist()
    }
}
