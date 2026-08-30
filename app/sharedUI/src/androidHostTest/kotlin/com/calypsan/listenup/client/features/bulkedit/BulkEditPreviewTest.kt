package com.calypsan.listenup.client.features.bulkedit

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.api.dto.BookGenreInput
import com.calypsan.listenup.client.domain.bulkedit.BulkEdit
import com.calypsan.listenup.core.GenreId
import com.calypsan.listenup.client.presentation.bulkedit.BulkEditPreviewRow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The honesty check before a change with no undo.
 *
 * A count that included books nothing happens to would overstate what Apply does, and it is also
 * how someone notices they selected the wrong forty while that is still free. A count on its own
 * would be honest and still useless — three rows reading "12 of 40", "40 of 40", "8 of 40" name
 * nothing you could act on — so each row is pinned to say which instruction it is describing.
 *
 * The device is tall enough that every row composes; at the default height these assertions would
 * be measuring the viewport rather than the code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h2400dp")
class BulkEditPreviewTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun render(
        rows: List<BulkEditPreviewRow>,
        bookCount: Int = 40,
    ) {
        composeRule.setContent {
            MaterialTheme { BulkEditPreview(rows = rows, bookCount = bookCount) }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `a row names how many of the selection it changes`() {
        render(listOf(BulkEditPreviewRow(BulkEdit.SetPublisher("Tor"), affectedCount = 12)))

        composeRule.onNodeWithText("12 of 40 books change", substring = true).assertIsDisplayed()
    }

    @Test
    fun `an instruction that changes nothing says so`() {
        render(listOf(BulkEditPreviewRow(BulkEdit.SetPublisher("Tor"), affectedCount = 0)))

        composeRule.onNodeWithText("No books change", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a row names the field it changes, so the count belongs to something`() {
        render(
            listOf(
                BulkEditPreviewRow(
                    BulkEdit.AddGenres(listOf(BookGenreInput(GenreId("grimdark")))),
                    affectedCount = 12,
                ),
                BulkEditPreviewRow(BulkEdit.SetPublisher("Tor"), affectedCount = 40),
            ),
        )

        composeRule.onNodeWithText("Add genres", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("12 of 40 books change", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Publisher", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("40 of 40 books change", substring = true).assertIsDisplayed()
    }

    @Test
    fun `one selected book is never told it is one of one`() {
        render(listOf(BulkEditPreviewRow(BulkEdit.SetPublisher("Tor"), affectedCount = 1)), bookCount = 1)

        composeRule.onNodeWithText("This book changes", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("1 of 1", substring = true).assertDoesNotExist()
    }
}
