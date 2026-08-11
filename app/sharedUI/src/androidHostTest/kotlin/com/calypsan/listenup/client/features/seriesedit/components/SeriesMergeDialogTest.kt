package com.calypsan.listenup.client.features.seriesedit.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.client.presentation.seriesedit.SeriesCandidate
import com.calypsan.listenup.core.SeriesId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A series merge re-points every book at the target and soft-deletes the source, and
 * nothing in the product can reverse it. The dialog has to say both how much moves and
 * that it is permanent before the admin commits.
 */
@RunWith(RobolectricTestRunner::class)
class SeriesMergeDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `the dialog states the book count and that the merge is permanent`() {
        composeRule.setContent {
            MaterialTheme {
                SeriesMergeDialog(
                    candidates = listOf(CANDIDATE),
                    query = "",
                    bookCount = PLURAL_BOOK_COUNT,
                    onQueryChange = {},
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText("$PLURAL_BOOK_COUNT books will move.").assertIsDisplayed()
        composeRule.onNodeWithText("This action cannot be undone.").assertIsDisplayed()
    }

    @Test
    fun `the dialog uses singular phrasing when only one book will move`() {
        composeRule.setContent {
            MaterialTheme {
                SeriesMergeDialog(
                    candidates = listOf(CANDIDATE),
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

    private companion object {
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
