package com.calypsan.listenup.client.features.bulkedit

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.requestFocus
import com.calypsan.listenup.client.domain.bulkedit.BulkEdit
import com.calypsan.listenup.client.presentation.bulkedit.BulkEditPreviewRow
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

    /**
     * A stray keystroke here arms a forty-book write that has no undo, so taking it back has to be
     * one tap and it has to be visible. The clear button reports the empty value through the same
     * handler typing uses, which is what keeps "no instruction" and "an instruction I removed" the
     * same state rather than two that can disagree.
     */
    @Test
    fun `an armed field offers a way to disarm it`() {
        val typed = mutableListOf<String>()
        render(
            BulkEditUiState.Editing(bookCount = 40, edits = listOf(BulkEdit.SetPublisher("Tor"))),
            onPublisher = { typed += it },
        )

        composeRule.onNodeWithContentDescription("Clear Publisher").performClick()

        assertEquals("", typed.last())
    }

    @Test
    fun `an untouched field has nothing to take back`() {
        render(BulkEditUiState.Editing(bookCount = 40, sharedPublisher = "Tor"))

        composeRule.onNodeWithContentDescription("Clear Publisher").assertDoesNotExist()
    }

    // ── The consequence line ────────────────────────────────────────────────────────────────────
    //
    // The placeholder says what the books hold; the consequence line says what leaving the field
    // alone — or not — will do to them. It is the sentence that makes a placeholder safe to read,
    // and it has to be right in all three of its states, because it is the only place the screen
    // says "and no book is written to" out loud.

    @Test
    fun `a field the whole selection agrees on says leaving it writes nothing`() {
        render(BulkEditUiState.Editing(bookCount = 37, sharedLanguage = "English"))

        composeRule
            .onNodeWithText("All 37 books say English. Leave it and no book is written to.")
            .assertIsDisplayed()
    }

    @Test
    fun `a field the selection disagrees on says so, and still writes nothing`() {
        // The other two agree, so the one "Differs" sentence on screen can only be the publisher's.
        render(
            BulkEditUiState.Editing(
                bookCount = 40,
                sharedPublisher = null,
                sharedPublishYear = 2012,
                sharedLanguage = "English",
            ),
        )

        composeRule
            .onNodeWithText("Differs across 40 books. Leave it and no book is written to.")
            .assertIsDisplayed()
    }

    /**
     * Each field counts its **own** books.
     *
     * Two instructions are deliberately in play, and the three numbers on screen are deliberately
     * all different: the publisher moves twelve books, the language moves thirty, and thirty-five
     * books are touched by one or the other. A field that reported the screen's total, or the size
     * of the selection, would read as true and be a lie about that field — which is the failure the
     * whole screen exists to not commit.
     */
    @Test
    fun `each typed field promises its own books, not the screen's total`() {
        render(
            BulkEditUiState.Editing(
                bookCount = 40,
                edits = listOf(BulkEdit.SetPublisher("Recorded Books"), BulkEdit.SetLanguage("English")),
                preview =
                    listOf(
                        BulkEditPreviewRow(BulkEdit.SetPublisher("Recorded Books"), affectedCount = 12),
                        BulkEditPreviewRow(BulkEdit.SetLanguage("English"), affectedCount = 30),
                    ),
                changedBookCount = 35,
            ),
        )

        composeRule.onNodeWithText("Written to 12 of 40 books.").assertIsDisplayed()
        composeRule.onNodeWithText("Written to 30 of 40 books.").assertIsDisplayed()
        // Neither the screen's total nor the selection's size belongs on a single field.
        composeRule.onNodeWithText("Written to 35 of 40 books.").assertDoesNotExist()
        composeRule.onNodeWithText("Written to 40 of 40 books.").assertDoesNotExist()
    }

    /**
     * A typed value every book already holds is the case a bare "12 of 40" cannot express: the
     * field looks armed and changes nothing. Saying so here is what stops the user reading the
     * coral outline as a promise.
     */
    @Test
    fun `a typed field that changes nothing admits it rather than counting to zero`() {
        render(
            BulkEditUiState.Editing(
                bookCount = 40,
                edits = listOf(BulkEdit.SetLanguage("English")),
                preview = listOf(BulkEditPreviewRow(BulkEdit.SetLanguage("English"), affectedCount = 0)),
            ),
        )

        composeRule.onNodeWithText("Written to no books — they already say this.").assertIsDisplayed()
        composeRule.onNodeWithText("Written to 0 of 40 books.").assertDoesNotExist()
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
