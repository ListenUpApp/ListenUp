package com.calypsan.listenup.client.features.auth.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [CodeBoxes] transforms its input (`normalizedCode()`), so with the old `String`-overload
 * `BasicTextField` the caller's echo NEVER equalled what was typed and the internal buffer was
 * reset against the stale caller value on every recomposition — under a lagging echo, whole bursts
 * of typed characters were silently dropped (masked only because the caret is invisible).
 *
 * These tests pin the fix: the component owns a local `TextFieldValue` holding the normalised
 * text, so typing survives a lagging echo, while a genuine external replacement (e.g. the
 * ViewModel clearing a rejected code) is still adopted. Echo staleness is simulated the same way
 * as in [com.calypsan.listenup.client.design.components.ListenUpTextFieldCaretTest]: recorded
 * emissions are played back frames later.
 */
@RunWith(RobolectricTestRunner::class)
class CodeBoxesTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `fast typing survives a lagging echo and lands normalised`() {
        var vmValue by mutableStateOf("")
        val emissions = mutableListOf<String>()
        composeRule.setContent {
            MaterialTheme {
                CodeBoxes(value = vmValue, onValueChange = { emissions += it })
            }
        }

        // Two bursts of typing while the echo is still in flight — lower case and the spoken
        // separator, exactly what a person keys while a code is read to them.
        composeRule.onNodeWithTag(CODE_FIELD_TAG).performTextInput("k4m9")
        composeRule.onNodeWithTag(CODE_FIELD_TAG).performTextInput("-tqw")

        // The echoes land late: first a stale one, then the up-to-date one.
        composeRule.runOnIdle { vmValue = emissions.first() }
        composeRule.waitForIdle()
        composeRule.runOnIdle { vmValue = emissions.last() }
        composeRule.waitForIdle()

        // The second burst appended to the first — nothing was dropped, everything normalised.
        emissions.last() shouldBe "K4M9TQW"
        composeRule.onNodeWithText("K").assertExists()
        composeRule.onNodeWithText("W").assertExists()
    }

    @Test
    fun `an external clear empties the cells`() {
        var vmValue by mutableStateOf("")
        val emissions = mutableListOf<String>()
        composeRule.setContent {
            MaterialTheme {
                CodeBoxes(value = vmValue, onValueChange = { emissions += it })
            }
        }

        composeRule.onNodeWithTag(CODE_FIELD_TAG).performTextInput("k4m9")
        // The echo settles before the ViewModel acts.
        composeRule.runOnIdle { vmValue = emissions.last() }
        composeRule.waitForIdle()

        // The ViewModel clears the code — a genuine external replacement, not an echo.
        composeRule.runOnIdle { vmValue = "" }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("K").assertDoesNotExist()
    }
}
