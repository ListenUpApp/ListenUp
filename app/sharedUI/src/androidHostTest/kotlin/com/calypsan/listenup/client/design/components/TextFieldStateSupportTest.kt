package com.calypsan.listenup.client.design.components

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The helpers every screen-owned text field leans on: what [edits] and [ForwardEdits] promise a
 * handler with a side effect, and that [transformedBy] and [limitedTo] are safe to run on text
 * they have already cleaned.
 */
@RunWith(RobolectricTestRunner::class)
class TextFieldStateSupportTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `forwarding does not fire for the seeded text, only for edits after it`() {
        val heard = mutableListOf<String>()
        lateinit var state: TextFieldState
        composeRule.setContent {
            MaterialTheme {
                state = rememberForwardingTextFieldState("saved") { heard += it }
            }
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { heard shouldBe emptyList() }

        composeRule.runOnIdle { state.setTextAndPlaceCursorAtEnd("saved!") }
        composeRule.waitForIdle()

        composeRule.runOnIdle { heard shouldBe listOf("saved!") }
    }

    @Test
    fun `a new key starts a fresh state seeded with the new text and says nothing`() {
        val heard = mutableListOf<String>()
        var key by mutableStateOf("first")
        var seed by mutableStateOf("one")
        lateinit var state: TextFieldState
        composeRule.setContent {
            MaterialTheme {
                state = rememberForwardingTextFieldState(seed, key = key) { heard += it }
            }
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            seed = "two"
            key = "second"
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            state.text.toString() shouldBe "two"
            heard shouldBe emptyList()
        }
    }

    @Test
    fun `transformedBy is idempotent on text it has already cleaned`() {
        val transform: (String) -> String = { it.filter(Char::isDigit).take(4) }
        listOf("19x8y4z", "", "abc", "12", "123456", "0000").forEach { raw ->
            val once = transform(raw)
            transform(once) shouldBe once
        }
    }

    @Test
    fun `transformedBy keeps the caret where a mid-text rejection happened`() {
        val state = TextFieldState("1984")
        state.edit {
            // The user pastes "x5" between the 9 and the 8; the caret follows the paste.
            replace(2, 2, "x5")
            placeCursorBeforeCharAt(4)
            with(transformedBy { it.filter(Char::isDigit) }) { transformInput() }
        }

        state.text.toString() shouldBe "19584"
        // After "195": the rejected letter shifted nothing past the caret.
        state.selection.end shouldBe 3
    }

    @Test
    fun `transformedBy reverts an edit it rejects entirely and leaves the caret alone`() {
        val state = TextFieldState("1984")
        state.edit {
            replace(2, 2, "x")
            placeCursorBeforeCharAt(3)
            with(transformedBy { it.filter(Char::isDigit) }) { transformInput() }
        }

        state.text.toString() shouldBe "1984"
        state.selection.end shouldBe 4
    }

    @Test
    fun `clearText empties a forwarding state and reports the empty text`() {
        val heard = mutableListOf<String>()
        lateinit var state: TextFieldState
        composeRule.setContent {
            MaterialTheme {
                state = rememberForwardingTextFieldState("query") { heard += it }
            }
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle { state.clearText() }
        composeRule.waitForIdle()

        composeRule.runOnIdle { heard shouldBe listOf("") }
    }

    @Test
    fun `limitEdit passes an edit that fits and clips one that does not`() {
        limitEdit("abcde", "abcdef", editedCaret = 6, maxLength = 8) shouldBe null
        limitEdit("abcde", "ab123456cde", editedCaret = 8, maxLength = 8) shouldBe
            LimitedEdit("ab123cde", caret = 5)
        limitEdit("abcde", "abxcde", editedCaret = 3, maxLength = 5) shouldBe LimitedEdit("abcde", caret = 3)
    }
}
