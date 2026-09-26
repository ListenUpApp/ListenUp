package com.calypsan.listenup.client.features.merge

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.api.dto.MergeReceipt
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.client.presentation.merge.MergeHistoryState
import com.calypsan.listenup.client.presentation.merge.MergeUndoOutcome
import com.calypsan.listenup.core.MergeReceiptId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The "Merged into this" list (#1061), shared by the series editor and the genre admin on Android.
 * An undo is asked for first — it moves books — and what came back is said in plain words after.
 */
@RunWith(RobolectricTestRunner::class)
class MergeHistoryListTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val receipt =
        MergeReceipt(
            id = MergeReceiptId("r1"),
            sourceName = "Stormlite",
            mergedAt = 1_700_000_000_000L,
            mergedByName = "Simon",
            bookCount = 4,
        )

    private fun render(
        state: MergeHistoryState,
        onUndo: (MergeReceiptId) -> Unit = {},
        onRetry: () -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme { MergeHistoryList(state = state, onUndo = onUndo, onRetry = onRetry) }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `each merge names what was merged in, how many books, and who did it`() {
        render(MergeHistoryState.Ready(listOf(receipt)))

        composeRule.onNodeWithText("Stormlite").assertIsDisplayed()
        composeRule.onNodeWithText("Up to 4 books", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("by Simon", substring = true).assertIsDisplayed()
    }

    @Test
    fun `undo asks first, and only the confirmation undoes`() {
        val undone = mutableListOf<MergeReceiptId>()
        render(MergeHistoryState.Ready(listOf(receipt)), onUndo = { undone += it })

        composeRule.onNodeWithText("Undo").performClick()
        composeRule.onNodeWithText("Undo this merge?").assertIsDisplayed()
        assert(undone.isEmpty()) { "nothing is undone before the confirmation, got $undone" }

        composeRule.onNodeWithText("Undo merge").performClick()

        assert(undone == listOf(MergeReceiptId("r1"))) { "the confirmed receipt is undone, got $undone" }
    }

    @Test
    fun `what came back is said plainly, including what stayed`() {
        render(
            MergeHistoryState.Ready(
                receipts = emptyList(),
                outcome = MergeUndoOutcome("Stormlite", booksRestored = 3, booksSkipped = 1, restoredAtTopLevel = false),
            ),
        )

        composeRule.onNodeWithText("“Stormlite” is back. 3 books moved back.").assertIsDisplayed()
        composeRule.onNodeWithText("1 book had changed since and stayed where it was.").assertIsDisplayed()
    }

    @Test
    fun `nothing merged says so, and says older merges cannot be undone`() {
        render(MergeHistoryState.Ready(emptyList()))

        composeRule.onNodeWithText("Nothing has been merged into this.").assertIsDisplayed()
        composeRule.onNodeWithText("Merges made before this version can't be undone.").assertIsDisplayed()
    }

    @Test
    fun `a list that could not be read says why and offers to try again`() {
        var retries = 0
        render(MergeHistoryState.Unavailable(TransportError.NetworkUnavailable()), onRetry = { retries++ })

        composeRule.onNodeWithText(TransportError.NetworkUnavailable().message).assertIsDisplayed()
        composeRule.onNodeWithText("Try again").performClick()

        assert(retries == 1) { "retry asks for the list again, got $retries" }
    }
}
