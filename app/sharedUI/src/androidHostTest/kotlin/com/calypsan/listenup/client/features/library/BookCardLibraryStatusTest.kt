package com.calypsan.listenup.client.features.library

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.client.design.components.BookCoverModel
import com.calypsan.listenup.client.presentation.library.BookCardStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Spec §2.6: a Library card's last line says where the reader is. */
@RunWith(RobolectricTestRunner::class)
class BookCardLibraryStatusTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun show(
        status: BookCardStatus,
        progressUnderTitle: Boolean = true,
    ) = composeRule.setContent {
        MaterialTheme {
            BookCard(
                cover =
                    BookCoverModel(
                        bookId = "b1",
                        title = "The Way of Kings",
                        author = "Brandon Sanderson",
                        coverPath = "/tmp/cover-b1.webp",
                        coverHash = null,
                    ),
                onClick = {},
                narrators = "Kate Reading, Michael Kramer",
                libraryStatus = status,
                progressUnderTitle = progressUnderTitle,
            )
        }
    }

    @Test
    fun `a started book says its time left and its narrators`() {
        show(BookCardStatus.InProgress(fraction = 0.11f, timeLeftMs = 145_860_000L))

        composeRule.onNodeWithText("40h 31m left").assertIsDisplayed()
        composeRule.onNodeWithText("Read by Kate Reading, Michael Kramer").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("11 percent listened", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `on a wide grid the progress sits on the art and still says how far along it is`() {
        show(BookCardStatus.InProgress(fraction = 0.11f, timeLeftMs = 145_860_000L), progressUnderTitle = false)

        composeRule.onNodeWithText("40h 31m left").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("11 percent listened", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `a finished book says Finished and its length, with the finished badge`() {
        show(BookCardStatus.Finished(durationMs = 43_440_000L))

        composeRule.onNodeWithText("Finished · 12h 4m").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Completed", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `an unstarted book says its length`() {
        show(BookCardStatus.NotStarted(durationMs = 43_440_000L))

        composeRule.onNodeWithText("12h 4m").assertIsDisplayed()
    }
}
