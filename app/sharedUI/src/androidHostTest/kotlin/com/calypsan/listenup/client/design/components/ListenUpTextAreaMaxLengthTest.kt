package com.calypsan.listenup.client.design.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [ListenUpTextArea]'s `maxLength` holds the text to the limit by dropping overflow from what an edit
 * INSERTS — never by cutting the text the user already wrote.
 */
@RunWith(RobolectricTestRunner::class)
class ListenUpTextAreaMaxLengthTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var note by mutableStateOf("")

    private fun showNote(
        initial: String,
        maxLength: Int?,
    ) {
        note = initial
        composeRule.setContent {
            MaterialTheme {
                ListenUpTextArea(
                    value = note,
                    onValueChange = { note = it },
                    label = "Note",
                    maxLength = maxLength,
                    modifier = Modifier.testTag(TAG),
                )
            }
        }
    }

    @Test
    fun `typing at the end at the limit is ignored`() {
        showNote("abcde", maxLength = 5)

        composeRule.onNodeWithTag(TAG).performTextInput("x")

        composeRule.runOnIdle { note shouldBe "abcde" }
    }

    @Test
    fun `typing mid-note at the limit leaves the tail intact`() {
        showNote("abcde", maxLength = 5)

        composeRule.onNodeWithTag(TAG).performTextInputSelection(TextRange(2))
        composeRule.onNodeWithTag(TAG).performTextInput("x")

        composeRule.runOnIdle { note shouldBe "abcde" }
    }

    @Test
    fun `a paste is clipped to what fits, keeping the text after it`() {
        showNote("abcde", maxLength = 8)

        composeRule.onNodeWithTag(TAG).performTextInputSelection(TextRange(2))
        composeRule.onNodeWithTag(TAG).performTextInput("123456")

        composeRule.runOnIdle { note shouldBe "ab123cde" }
    }

    @Test
    fun `an emoji is never split in half`() {
        showNote("abcd", maxLength = 5)

        // "😀" is two UTF-16 chars; only one fits, and half a surrogate pair is not a character.
        composeRule.onNodeWithTag(TAG).performTextInput("😀")

        composeRule.runOnIdle { note shouldBe "abcd" }
    }

    @Test
    fun `without a maxLength nothing is ever truncated`() {
        val long = "a".repeat(400)
        showNote(long, maxLength = null)

        composeRule.onNodeWithTag(TAG).performTextInput("bcd")

        composeRule.runOnIdle { note shouldBe long + "bcd" }
    }

    private companion object {
        const val TAG = "note"
    }
}
