package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.api.dto.hardcover.HardcoverBookSync
import com.calypsan.listenup.client.presentation.hardcover.BookHardcoverUiState
import com.calypsan.listenup.client.presentation.hardcover.HardcoverMatchedBook
import com.calypsan.listenup.client.presentation.hardcover.KeepOffRemoves
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private val MATCH = HardcoverMatchedBook(427_578L, "Project Hail Mary", listOf("Andy Weir"), 2021, chosenByYou = true)

/** Spec B5's Book Detail card, in each of its states. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class BookHardcoverSectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var opens = 0
    private var removes = 0
    private val syncs = mutableListOf<Boolean>()

    private fun render(state: BookHardcoverUiState) {
        composeRule.setContent {
            MaterialTheme {
                BookHardcoverContent(
                    state = state,
                    onFindMatch = { opens++ },
                    onRemoveMatch = { removes++ },
                    onSetSynced = { syncs += it },
                )
            }
        }
    }

    @Test
    fun `needs a match explains itself and opens Find on Hardcover`() {
        render(BookHardcoverUiState.NeedsMatch)
        composeRule.onNodeWithText("Needs a match").assertIsDisplayed()
        composeRule.onNodeWithText("Pick the right book so your listening syncs.").assertIsDisplayed()
        composeRule.onNodeWithText("Find on Hardcover").performClick()
        opens shouldBe 1
    }

    @Test
    fun `a linked book shows what it is matched to, who chose it, and where it stands`() {
        render(BookHardcoverUiState.Linked(MATCH, HardcoverBookSync.WAITING))
        composeRule.onNodeWithText("Sync with Hardcover").assertIsOn()
        composeRule.onNodeWithText("Project Hail Mary").assertIsDisplayed()
        composeRule.onNodeWithText("Andy Weir · 2021").assertIsDisplayed()
        composeRule.onNodeWithText("Matched by you").assertIsDisplayed()
        composeRule.onNodeWithText("Updating…").assertIsDisplayed()
        composeRule.onNodeWithText("Change match").performClick()
        opens shouldBe 1
    }

    @Test
    fun `Remove match removes it`() {
        render(BookHardcoverUiState.Linked(MATCH, HardcoverBookSync.UP_TO_DATE))
        composeRule.onNodeWithText("Remove match").performClick()
        removes shouldBe 1
    }

    @Test
    fun `up to date says so`() {
        render(BookHardcoverUiState.Linked(MATCH, HardcoverBookSync.UP_TO_DATE))
        composeRule.onNodeWithText("Up to date").assertIsDisplayed()
    }

    @Test
    fun `nothing sent yet says so`() {
        render(BookHardcoverUiState.Linked(MATCH, HardcoverBookSync.NOTHING_SENT_YET))
        composeRule.onNodeWithText("Not sent yet").assertIsDisplayed()
    }

    @Test
    fun `removed on Hardcover says the sync stopped`() {
        render(BookHardcoverUiState.Linked(MATCH, HardcoverBookSync.REMOVED_ON_HARDCOVER))
        composeRule
            .onNodeWithText("Stopped — you removed it on Hardcover")
            .assertIsDisplayed()
    }

    @Test
    fun `a fresh match reads Matched just now in place of where it stands`() {
        render(BookHardcoverUiState.Linked(MATCH, HardcoverBookSync.NOTHING_SENT_YET, justMatched = true))
        composeRule.onNodeWithText("Matched just now").assertIsDisplayed()
        composeRule.onNodeWithText("Not sent yet").assertDoesNotExist()
    }

    @Test
    fun `a match Hardcover couldn't name still says it is matched`() {
        render(BookHardcoverUiState.Linked(MATCH.copy(title = null), HardcoverBookSync.UP_TO_DATE))
        composeRule.onNodeWithText("Matched on Hardcover").assertIsDisplayed()
    }

    @Test
    fun `hidden draws nothing`() {
        render(BookHardcoverUiState.Hidden)
        composeRule.onNodeWithText("On Hardcover").assertDoesNotExist()
        composeRule.onNodeWithText("Hardcover").assertDoesNotExist()
        composeRule.onNodeWithText("Sync with Hardcover").assertDoesNotExist()
    }

    @Test
    fun `a book kept off collapses to one calm line, its switch off and no match actions`() {
        render(BookHardcoverUiState.KeptOff())
        composeRule.onNodeWithText("Sync with Hardcover").assertIsOff()
        composeRule
            .onNodeWithText("Kept off Hardcover — nothing about this book is shared or brought in.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Change match").assertDoesNotExist()
        composeRule.onNodeWithText("Find on Hardcover").assertDoesNotExist()
    }

    @Test
    fun `switching it back on syncs it again, with no dialog`() {
        render(BookHardcoverUiState.KeptOff())
        composeRule.onNodeWithText("Sync with Hardcover").performClick()
        syncs shouldBe listOf(true)
        composeRule.onNodeWithText("Keep this book off Hardcover?").assertDoesNotExist()
    }

    @Test
    fun `while it syncs again the switch already reads on, and the kept-off line has gone`() {
        render(BookHardcoverUiState.KeptOff(isResuming = true))
        composeRule.onNodeWithText("Sync with Hardcover").assertIsOn()
        composeRule
            .onNodeWithText("Kept off Hardcover — nothing about this book is shared or brought in.")
            .assertDoesNotExist()
    }

    @Test
    fun `with nothing visible to lose, switching off keeps it off at once`() {
        render(BookHardcoverUiState.Linked(MATCH, HardcoverBookSync.UP_TO_DATE))
        composeRule.onNodeWithText("Sync with Hardcover").performClick()
        syncs shouldBe listOf(false)
        composeRule.onNodeWithText("Keep this book off Hardcover?").assertDoesNotExist()
    }

    @Test
    fun `with Hardcover reads and a To Read entry, it asks first, and Keep off keeps it off`() {
        render(
            BookHardcoverUiState.Linked(
                MATCH,
                HardcoverBookSync.UP_TO_DATE,
                keepOffRemoves = KeepOffRemoves.READS_AND_TO_READ,
            ),
        )
        composeRule.onNodeWithText("Sync with Hardcover").performClick()
        syncs shouldBe emptyList()
        composeRule.onNodeWithText("Keep this book off Hardcover?").assertIsDisplayed()
        composeRule
            .onNodeWithText(
                "Its Hardcover reads leave Readers here and it comes off your To Read shelf. Nothing on Hardcover changes.",
            ).assertIsDisplayed()
        composeRule.onNodeWithText("Keep off").performClick()
        syncs shouldBe listOf(false)
    }

    @Test
    fun `Cancel leaves it syncing`() {
        render(BookHardcoverUiState.Linked(MATCH, HardcoverBookSync.UP_TO_DATE, keepOffRemoves = KeepOffRemoves.READS))
        composeRule.onNodeWithText("Sync with Hardcover").performClick()
        composeRule
            .onNodeWithText("Its Hardcover reads leave Readers here. Nothing on Hardcover changes.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performClick()
        syncs shouldBe emptyList()
        composeRule.onNodeWithText("Keep this book off Hardcover?").assertDoesNotExist()
        composeRule.onNodeWithText("Sync with Hardcover").assertIsOn()
    }

    @Test
    fun `a To Read entry alone is named alone`() {
        render(BookHardcoverUiState.Linked(MATCH, HardcoverBookSync.UP_TO_DATE, keepOffRemoves = KeepOffRemoves.TO_READ))
        composeRule.onNodeWithText("Sync with Hardcover").performClick()
        composeRule.onNodeWithText("It comes off your To Read shelf. Nothing on Hardcover changes.").assertIsDisplayed()
    }

    @Test
    fun `a book that needs a match offers the switch too`() {
        render(BookHardcoverUiState.NeedsMatch)
        composeRule.onNodeWithText("Sync with Hardcover").assertIsOn().performClick()
        syncs shouldBe listOf(false)
    }

    // Decision 1: every book has the switch while Hardcover is connected.
    @Test
    fun `a book never matched shows the switch on, with no actions, and switching it off asks nothing`() {
        render(BookHardcoverUiState.Unmatched)
        composeRule.onNodeWithText("Sync with Hardcover").assertIsOn().performClick()
        syncs shouldBe listOf(false)
        composeRule.onNodeWithText("Keep this book off Hardcover?").assertDoesNotExist()
        composeRule.onNodeWithText("Find on Hardcover").assertDoesNotExist()
        composeRule.onNodeWithText("Change match").assertDoesNotExist()
    }
}
