package com.calypsan.listenup.client.features.bookdetail

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.domain.model.BookDetail
import com.calypsan.listenup.client.features.bookdetail.components.BookActionsMenu
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailMenuItem
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailUiState
import com.calypsan.listenup.client.presentation.bookdetail.ReadingStage
import com.calypsan.listenup.client.presentation.bookdetail.bookDetailMoreMenu
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Book Detail's ⋮ menu (spec §2.7): its groups per role and stage, and Delete alone in its own group. */
@RunWith(RobolectricTestRunner::class)
class BookDetailMoreMenuTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val chosen = mutableListOf<BookDetailMenuItem>()

    private fun show(
        stage: ReadingStage = ReadingStage.InProgress,
        isAdmin: Boolean = false,
        canEditMetadata: Boolean = false,
        busy: Boolean = false,
    ) {
        val ready =
            BookDetailUiState.Ready(
                book =
                    BookDetail(
                        id = BookId("b1"),
                        libraryId = LibraryId("l"),
                        folderId = FolderId("f"),
                        title = "Circe",
                        authors = emptyList(),
                        narrators = emptyList(),
                        duration = 1L,
                        coverPath = null,
                        addedAt = Timestamp(0L),
                        updatedAt = Timestamp(0L),
                    ),
                readingStage = stage,
                isComplete = stage == ReadingStage.Finished,
                isAdmin = isAdmin,
                canEditMetadata = canEditMetadata,
                isRestarting = busy,
            )
        composeRule.setContent {
            MaterialTheme {
                BookActionsMenu(
                    expanded = true,
                    onDismiss = {},
                    groups = bookDetailMoreMenu(ready),
                    progressBusy = ready.isChangingProgress,
                    onItem = { chosen += it },
                )
            }
        }
    }

    private fun group(key: String): SemanticsNodeInteraction = composeRule.onNodeWithTag("bookMenuGroup:$key")

    @Test
    fun `a member reading the book gets Share then the three progress actions under Progress`() {
        show()
        group("share").onChildren().filterToOne(hasText("Share…")).assertExists()
        group("progress").onChildren().filterToOne(hasText("Progress")).assertExists()
        composeRule.onNodeWithText("Mark as finished…").assertExists()
        composeRule.onNodeWithText("Restart from the beginning…").assertExists()
        composeRule.onNodeWithText("Mark as not started…").assertExists()
        group("edit").assertDoesNotExist()
        group("admin").assertDoesNotExist()
        group("delete").assertDoesNotExist()
        composeRule.onNodeWithText("Add to shelf", substring = true).assertDoesNotExist()
    }

    @Test
    fun `finished, Mark as finished leaves the menu`() {
        show(ReadingStage.Finished)
        composeRule.onNodeWithText("Mark as finished…").assertDoesNotExist()
        composeRule.onNodeWithText("Restart from the beginning…").assertExists()
    }

    @Test
    fun `not started, the two resets are absent`() {
        show(ReadingStage.NotStarted)
        composeRule.onNodeWithText("Restart from the beginning…").assertDoesNotExist()
        composeRule.onNodeWithText("Mark as not started…").assertDoesNotExist()
    }

    @Test
    fun `Edit metadata adds the Edit group with its three actions`() {
        show(canEditMetadata = true)
        group("edit").onChildren().filterToOne(hasText("Edit")).assertExists()
        composeRule.onNodeWithText("Edit details").assertExists()
        composeRule.onNodeWithText("Match details…").assertExists()
        composeRule.onNodeWithText("Edit chapters").assertExists()
    }

    @Test
    fun `an admin gets Admin, and Delete alone in its own group`() {
        show(isAdmin = true, canEditMetadata = true)
        group("admin").onChildren().filterToOne(hasText("Add to collection…")).assertExists()
        // Exactly one item in the delete group, and it is Delete: nothing reversible shares its group.
        group("delete").onChildren().fetchSemanticsNodes().size shouldBe 1
        group("delete").onChildren().filterToOne(hasText("Delete from server…")).assertExists()
    }

    @Test
    fun `while a progress write is in flight, only the progress items wait`() {
        show(isAdmin = true, busy = true)
        composeRule.onNodeWithText("Mark as finished…").assertIsNotEnabled()
        composeRule.onNodeWithText("Restart from the beginning…").assertIsNotEnabled()
        composeRule.onNodeWithText("Share…").performClick()
        composeRule.runOnIdle { chosen shouldBe listOf(BookDetailMenuItem.Share) }
    }
}
