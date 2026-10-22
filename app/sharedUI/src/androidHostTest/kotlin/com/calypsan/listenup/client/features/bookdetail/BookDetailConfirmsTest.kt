package com.calypsan.listenup.client.features.bookdetail

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import com.calypsan.listenup.client.features.bookdetail.components.MarkNotStartedDialog
import com.calypsan.listenup.client.features.bookdetail.components.RestartBookDialog
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.api.error.BookError
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Book Detail's two confirms say what goes (spec §2.8): Delete from the server, and Remove download. */
@RunWith(RobolectricTestRunner::class)
class BookDetailConfirmsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `Delete from the server says how many files, how big, the folder, every member and no undo`() {
        composeRule.setContent {
            MaterialTheme {
                DeleteBookDialog(
                    bookTitle = "The Way of Kings",
                    trackedFileCount = 76,
                    trackedBytes = 1_288_490_189L,
                    error = null,
                    isDeleting = false,
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }
        // One wording on every platform (the rig drive found the title without the book on Android).
        composeRule.onNodeWithText("Delete “The Way of Kings” from the server?").assertExists()
        composeRule
            .onNodeWithText(
                "This deletes 76 files (1.2 GB) and everything else in its folder from your server. Every member " +
                    "loses it, and downloads are removed from their devices. This can’t be undone.",
            ).assertExists()
        composeRule.onNodeWithText("Delete book").assertExists()
        // Spec §5.2: focus starts on Cancel, the safe way out.
        composeRule.onNodeWithText("Cancel").assertIsFocused()
    }

    @Test
    fun `one file reads as one file`() {
        composeRule.setContent {
            MaterialTheme {
                DeleteBookDialog(
                    "Circe",
                    trackedFileCount = 1,
                    trackedBytes = 1_024L,
                    error = null,
                    isDeleting = false,
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.onNodeWithText("This deletes 1 file (1 KB) and everything else", substring = true).assertExists()
    }

    @Test
    fun `a typed refusal stays in the dialog and names the blocking book`() {
        composeRule.setContent {
            MaterialTheme {
                DeleteBookDialog(
                    "Circe",
                    3,
                    3_000L,
                    error = BookError.FolderNotExclusive(otherBookId = "b2", otherBookTitle = "Joyland"),
                    isDeleting = false,
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.onNodeWithText("“Joyland” is in the same folder", substring = true).assertExists()
    }

    @Test
    fun `Restart and Mark as not started ask in the menu's own words`() {
        composeRule.setContent {
            MaterialTheme {
                Column {
                    RestartBookDialog(onConfirm = {}, onDismiss = {})
                }
            }
        }
        composeRule.onNodeWithText("Restart from the beginning?").assertExists()
        composeRule.onNodeWithText("Your place goes back to the start of the book.").assertExists()
        composeRule.onNodeWithText("Restart").assertExists()
    }

    @Test
    fun `Mark as not started asks in the menu's own words`() {
        composeRule.setContent { MaterialTheme { MarkNotStartedDialog(onConfirm = {}, onDismiss = {}) } }
        composeRule.onNodeWithText("Mark as not started?").assertExists()
        composeRule.onNodeWithText("Your progress on this book is cleared.").assertExists()
    }

    @Test
    fun `Remove download says what it frees and that the place is kept, with Keep and Remove`() {
        var removed = 0
        composeRule.setContent {
            MaterialTheme {
                DeleteDownloadDialog(downloadSize = 1_288_490_189L, onConfirm = { removed++ }, onDismiss = {})
            }
        }
        composeRule.onNodeWithText("Remove download?").assertExists()
        composeRule
            .onNodeWithText(
                "Frees 1.2 GB on this device. The book stays in your library, keeps your place, and streams when you’re online.",
            ).assertExists()
        composeRule.onNodeWithText("Keep").assertExists()
        composeRule.onNodeWithText("Remove").performClick()
        composeRule.runOnIdle { removed shouldBe 1 }
    }
}
