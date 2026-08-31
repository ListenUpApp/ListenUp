package com.calypsan.listenup.client.features.bulkedit

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.requestFocus
import com.calypsan.listenup.client.presentation.bulkedit.BulkEditUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The form, and the rule that keeps it safe.
 *
 * A shared value is shown as *hint* text and never as a value, so "the user typed something" and
 * "this field produces an instruction" stay the same condition. Pre-filling would make saving
 * rewrite fields nobody touched — so the assertion that matters is not that the hint appears, it is
 * that the field is still **empty** while it appears.
 *
 * Material reveals a placeholder only once the label is out of its way, so the hint assertions
 * focus the field first. That is the moment the hint has a job to do.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h2400dp")
class BulkEditFormTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun render(
        state: BulkEditUiState.Editing,
        onPublisher: (String) -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                BulkEditForm(
                    state = state,
                    onPublisherChange = onPublisher,
                    onYearChange = {},
                    onLanguageChange = {},
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `a shared value is a hint, not a value`() {
        val typed = mutableListOf<String>()
        render(BulkEditUiState.Editing(bookCount = 40, sharedPublisher = "Tor"), onPublisher = { typed += it })

        composeRule.onNodeWithText("Publisher").requestFocus()

        // Present as a hint...
        composeRule.onNodeWithText("Tor").assertIsDisplayed()
        // ...and nowhere else: nothing was written, so Apply has nothing to write.
        composeRule.onNodeWithText("Publisher").assertNothingTyped()
        assertEquals(emptyList<String>(), typed)
    }

    @Test
    fun `books that disagree show Multiple values`() {
        render(BulkEditUiState.Editing(bookCount = 40, sharedPublisher = null))

        composeRule.onNodeWithText("Publisher").requestFocus()

        composeRule.onNodeWithText("Multiple values", substring = true).assertIsDisplayed()
    }

    @Test
    fun `typing a publisher reports it`() {
        val typed = mutableListOf<String>()
        render(BulkEditUiState.Editing(bookCount = 40), onPublisher = { typed += it })

        composeRule.onNodeWithText("Publisher").performTextInput("Tor")

        assertEquals("Tor", typed.last())
    }
}

/**
 * Asserts the field holds no text of its own — the only thing Apply can write.
 *
 * Tolerates the property being absent as well as empty: either way nothing was typed. A field that
 * pre-filled itself from the selection's shared value fails here, which is the whole point.
 */
private fun SemanticsNodeInteraction.assertNothingTyped(): SemanticsNodeInteraction =
    assert(
        SemanticsMatcher("${SemanticsProperties.EditableText.name} is empty") { node ->
            node.config
                .getOrNull(SemanticsProperties.EditableText)
                ?.text
                .isNullOrEmpty()
        },
    )
