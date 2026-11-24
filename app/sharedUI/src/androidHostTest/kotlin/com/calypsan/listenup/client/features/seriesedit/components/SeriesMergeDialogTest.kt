package com.calypsan.listenup.client.features.seriesedit.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.client.presentation.seriesedit.SeriesCandidate
import com.calypsan.listenup.core.SeriesId
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A series merge re-points every book at the target and soft-deletes the source, and
 * nothing in the product can reverse it. The dialog has to say both how much moves and
 * where it can be undone before the admin commits.
 */
@RunWith(RobolectricTestRunner::class)
class SeriesMergeDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `the dialog states the book count and where the merge can be undone`() {
        composeRule.setContent {
            MaterialTheme {
                SeriesMergeDialog(
                    candidates = listOf(CANDIDATE),
                    truncated = false,
                    query = "",
                    bookCount = PLURAL_BOOK_COUNT,
                    onQueryChange = {},
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText("$PLURAL_BOOK_COUNT books will move.").assertIsDisplayed()
        composeRule.onNodeWithText("You can undo this later from its merge history.").assertIsDisplayed()
    }

    @Test
    fun `the dialog uses singular phrasing when only one book will move`() {
        composeRule.setContent {
            MaterialTheme {
                SeriesMergeDialog(
                    candidates = listOf(CANDIDATE),
                    truncated = false,
                    query = "",
                    bookCount = SINGULAR_BOOK_COUNT,
                    onQueryChange = {},
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText("$SINGULAR_BOOK_COUNT book will move.").assertIsDisplayed()
    }

    @Test
    fun `a chosen target the search has hidden cannot be merged into`() {
        var shown by mutableStateOf(listOf(CANDIDATE, OTHER))
        val merged = mutableListOf<SeriesId>()
        composeRule.setContent {
            MaterialTheme {
                SeriesMergeDialog(
                    candidates = shown,
                    truncated = false,
                    query = "",
                    bookCount = PLURAL_BOOK_COUNT,
                    onQueryChange = {},
                    onConfirm = { merged += it },
                    onDismiss = {},
                )
            }
        }
        composeRule.onNodeWithText(CANDIDATE.displayName).performClick()
        composeRule.onNodeWithText(CONFIRM).assertIsEnabled()

        shown = listOf(OTHER)
        composeRule.waitForIdle()

        composeRule.onNodeWithText(CONFIRM).assertIsNotEnabled()
        merged shouldBe emptyList()
    }

    @Test
    fun `candidates are radio rows that announce which one is chosen`() {
        composeRule.setContent {
            MaterialTheme {
                SeriesMergeDialog(
                    candidates = listOf(CANDIDATE, OTHER),
                    truncated = false,
                    query = "",
                    bookCount = PLURAL_BOOK_COUNT,
                    onQueryChange = {},
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText(OTHER.displayName).performClick()

        composeRule.onNode(hasText(OTHER.displayName) and radioRow).assertIsSelected()
        composeRule.onNode(hasText(CANDIDATE.displayName) and radioRow).assertIsNotSelected()
    }

    private companion object {
        const val CONFIRM = "Merge"
        val radioRow = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

        val OTHER =
            SeriesCandidate(
                id = SeriesId("series-3"),
                displayName = "Mistborn",
                bookCount = 0,
            )

        const val PLURAL_BOOK_COUNT = 4
        const val SINGULAR_BOOK_COUNT = 1

        val CANDIDATE =
            SeriesCandidate(
                id = SeriesId("series-2"),
                displayName = "The Stormlight Archive",
                bookCount = 0,
            )
    }
}
